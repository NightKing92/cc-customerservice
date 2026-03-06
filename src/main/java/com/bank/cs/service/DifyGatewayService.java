package com.bank.cs.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dify 网关服务
 *
 * 负责调用 Dify Chatflow API，解析 agent_thought / message 事件，
 * 转换为前端可直接渲染的统一事件格式：
 *
 *   {"type":"thinking",  "content":"..."}          // LLM 思考过程
 *   {"type":"tool_call", "tool":"...", "input":"..."} // 工具调用
 *   {"type":"tool_result","tool":"...","status":"SUCCESS|BLOCKED_BY_RISK","content":"..."} // 工具结果
 *   {"type":"text",      "content":"..."}          // 最终回复 token
 *   {"type":"done"}                                 // 结束
 */
@Service
public class DifyGatewayService {

    private static final Logger log = LoggerFactory.getLogger(DifyGatewayService.class);

    private final WebClient difyClient;
    private final ObjectMapper objectMapper;
    private final String defaultUserId;

    // sessionId -> dify conversation_id，保持多轮对话记忆
    private final Map<String, String> conversationMap = new ConcurrentHashMap<>();

    public DifyGatewayService(
            @Value("${dify.api-key}") String apiKey,
            @Value("${dify.base-url}") String baseUrl,
            @Value("${dify.user-id}") String defaultUserId) {
        this.difyClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        this.objectMapper = new ObjectMapper();
        this.defaultUserId = defaultUserId;
    }

    /**
     * 发送消息到 Dify，返回统一格式的事件流
     */
    public Flux<String> chat(String sessionId, String userMessage) {
        String conversationId = conversationMap.getOrDefault(sessionId, "");
        String userId = defaultUserId;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", Map.of("user_id", userId));
        body.put("query", userMessage);
        body.put("response_mode", "streaming");
        body.put("user", userId);
        if (!conversationId.isEmpty()) {
            body.put("conversation_id", conversationId);
        }

        log.info("[Dify] session={}, conversationId={}, query={}", sessionId, conversationId, userMessage);

        return difyClient.post()
                .uri("/v1/chat-messages")
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(line -> !line.isBlank() && !line.equals("[DONE]"))
                .flatMap(line -> parseAndTransform(line, sessionId))
                .filter(Objects::nonNull)
                .onErrorResume(e -> {
                    log.error("[Dify] Stream error", e);
                    return Flux.just(toSseData("{\"type\":\"error\",\"content\":\"" + e.getMessage() + "\"}"));
                });
    }

    /**
     * 清除会话对应的 Dify conversation，开启新对话
     */
    public void clearSession(String sessionId) {
        conversationMap.remove(sessionId);
    }

    // ==================== 事件解析 ====================

    private Flux<String> parseAndTransform(String line, String sessionId) {
        // SSE 格式：去掉 "data: " 前缀
        String data = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
        if (data.isEmpty()) return Flux.empty();

        try {
            JsonNode node = objectMapper.readTree(data);
            String event = node.path("event").asText("");

            return switch (event) {
                case "agent_thought" -> handleAgentThought(node);
                case "message" -> handleMessage(node);
                case "message_end" -> handleMessageEnd(node, sessionId);
                // Chatflow workflow 事件也可能出现
                case "workflow_started", "node_started", "node_finished",
                     "workflow_finished" -> Flux.empty(); // 内部事件不透传
                case "text_chunk" -> handleTextChunk(node);
                default -> Flux.empty();
            };
        } catch (Exception e) {
            log.debug("[Dify] Parse skip: {}", data.substring(0, Math.min(data.length(), 80)));
            return Flux.empty();
        }
    }

    /**
     * agent_thought 事件：包含思考过程、工具调用、工具返回
     * Dify 会多次发送此事件，逐步填充 thought / tool / tool_input / observation
     */
    private Flux<String> handleAgentThought(JsonNode node) {
        String thought = node.path("thought").asText("").trim();
        String tool = node.path("tool").asText("").trim();
        String toolInput = node.path("tool_input").asText("").trim();
        String observation = node.path("observation").asText("").trim();

        // 有思考内容
        if (!thought.isEmpty()) {
            String payload = toJson(Map.of("type", "thinking", "content", thought));
            return Flux.just(toSseData(payload));
        }

        // 有工具调用（tool + tool_input 出现）
        if (!tool.isEmpty() && !toolInput.isEmpty() && observation.isEmpty()) {
            // 格式化参数展示
            String formattedInput = formatToolInput(toolInput);
            String payload = toJson(Map.of(
                    "type", "tool_call",
                    "tool", tool,
                    "input", formattedInput
            ));
            return Flux.just(toSseData(payload));
        }

        // 有工具结果（observation 出现）
        if (!observation.isEmpty()) {
            boolean isBlocked = observation.contains("BLOCKED_BY_RISK");
            String status = isBlocked ? "BLOCKED_BY_RISK" : "SUCCESS";
            String displayContent = extractObservation(observation);
            String payload = toJson(Map.of(
                    "type", "tool_result",
                    "tool", tool,
                    "status", status,
                    "content", displayContent
            ));
            return Flux.just(toSseData(payload));
        }

        return Flux.empty();
    }

    /**
     * message 事件：最终回复的流式 token
     */
    private Flux<String> handleMessage(JsonNode node) {
        String answer = node.path("answer").asText("");
        if (answer.isEmpty()) return Flux.empty();
        String payload = toJson(Map.of("type", "text", "content", answer));
        return Flux.just(toSseData(payload));
    }

    /**
     * text_chunk 事件：Chatflow 模式下的文字块
     */
    private Flux<String> handleTextChunk(JsonNode node) {
        String text = node.path("data").path("text").asText("");
        if (text.isEmpty()) return Flux.empty();
        String payload = toJson(Map.of("type", "text", "content", text));
        return Flux.just(toSseData(payload));
    }

    /**
     * message_end 事件：保存 conversation_id，发送结束信号
     */
    private Flux<String> handleMessageEnd(JsonNode node, String sessionId) {
        String conversationId = node.path("conversation_id").asText("");
        if (!conversationId.isEmpty()) {
            conversationMap.put(sessionId, conversationId);
            log.info("[Dify] conversation_id saved: session={}, convId={}", sessionId, conversationId);
        }
        return Flux.just(toSseData("{\"type\":\"done\"}"));
    }

    // ==================== 格式化工具方法 ====================

    private String formatToolInput(String toolInputJson) {
        try {
            JsonNode input = objectMapper.readTree(toolInputJson);
            StringBuilder sb = new StringBuilder();
            input.fields().forEachRemaining(entry ->
                    sb.append(entry.getKey()).append(": ").append(entry.getValue().asText()).append(" | ")
            );
            return sb.length() > 3 ? sb.substring(0, sb.length() - 3) : toolInputJson;
        } catch (Exception e) {
            return toolInputJson;
        }
    }

    private String extractObservation(String observation) {
        try {
            JsonNode node = objectMapper.readTree(observation);
            // 优先使用 observation 字段，其次 status
            if (node.has("observation")) return node.path("observation").asText();
            if (node.has("status")) {
                String status = node.path("status").asText();
                if ("BLOCKED_BY_RISK".equals(status)) {
                    return node.path("observation").asText(observation);
                }
            }
            return observation.length() > 200 ? observation.substring(0, 200) + "..." : observation;
        } catch (Exception e) {
            return observation.length() > 200 ? observation.substring(0, 200) + "..." : observation;
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toSseData(String json) {
        return "data: " + json + "\n\n";
    }
}
