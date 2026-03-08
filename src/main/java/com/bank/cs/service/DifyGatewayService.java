package com.bank.cs.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dify 网关服务
 *
 * 调用 Dify Chatflow API，手动解析 SSE 流，
 * 转换为前端可直接渲染的统一事件格式。
 *
 * Dify Chatflow 事件类型：
 *   workflow_started / node_started / node_finished → 内部流转（提取 Agent 思维链）
 *   agent_thought  → Agent 的思考、工具调用、工具返回
 *   agent_message  → Agent 流式回复 token
 *   text_chunk     → Answer 节点流式文本
 *   message        → 完整消息（非流式场景）
 *   message_end    → 结束，含 conversation_id
 */
@Service
public class DifyGatewayService {

    private static final Logger log = LoggerFactory.getLogger(DifyGatewayService.class);

    private final WebClient difyClient;
    private final ObjectMapper objectMapper;
    private final String defaultUserId;

    private final Map<String, String> conversationMap = new ConcurrentHashMap<>();

    public DifyGatewayService(
            @Value("${dify.api-key}") String apiKey,
            @Value("${dify.base-url}") String baseUrl,
            @Value("${dify.user-id}") String defaultUserId) {
        this.difyClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        this.objectMapper = new ObjectMapper();
        this.defaultUserId = defaultUserId;
    }

    public Flux<String> chat(String sessionId, String userMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", Map.of("query", userMessage));
        body.put("response_mode", "streaming");
        body.put("user", defaultUserId);

        log.info("[Dify] session={}, query={}", sessionId, userMessage);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 用 DataBuffer 接收原始字节流，手动解析 SSE
        difyClient.post()
                .uri("/v1/workflows/run")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(buffer -> {
                    String chunk = buffer.toString(StandardCharsets.UTF_8);
                    DataBufferUtils.release(buffer);
                    return chunk;
                })
                .subscribe(
                        chunk -> processChunk(chunk, sessionId, sink),
                        error -> {
                            log.error("[Dify] Stream error", error);
                            sink.tryEmitNext(toSseData(toJson(Map.of(
                                    "type", "error",
                                    "content", error.getMessage() != null ? error.getMessage() : "Unknown error"
                            ))));
                            sink.tryEmitComplete();
                        },
                        () -> {
                            log.info("[Dify] Stream completed for session={}", sessionId);
                            sink.tryEmitComplete();
                        }
                );

        return sink.asFlux();
    }

    public void clearSession(String sessionId) {
        conversationMap.remove(sessionId);
        thinkStateMap.remove(sessionId);
    }

    // ==================== SSE 原始流解析 ====================

    private final Map<String, StringBuilder> bufferMap = new ConcurrentHashMap<>();

    private void processChunk(String chunk, String sessionId, Sinks.Many<String> sink) {
        // 拼接缓冲区（SSE 事件可能跨 chunk 边界）
        StringBuilder buffer = bufferMap.computeIfAbsent(sessionId, k -> new StringBuilder());
        buffer.append(chunk);

        // 按换行分割，寻找完整的 SSE 事件
        String content = buffer.toString();
        String[] lines = content.split("\n");

        // 如果最后一行不以换行结尾，保留为未完成部分
        boolean endsWithNewline = content.endsWith("\n");
        buffer.setLength(0);
        if (!endsWithNewline && lines.length > 0) {
            buffer.append(lines[lines.length - 1]);
            String[] complete = new String[lines.length - 1];
            System.arraycopy(lines, 0, complete, 0, lines.length - 1);
            lines = complete;
        }

        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                log.info("[Dify-RAW] {}", trimmed.substring(0, Math.min(trimmed.length(), 150)));
            }
            if (trimmed.startsWith("data:")) {
                String data = trimmed.substring(5).trim();
                if (!data.isEmpty()) {
                    handleSseData(data, sessionId, sink);
                }
            }
            // event: ping 等非 data 行直接忽略
        }
    }

    private void handleSseData(String data, String sessionId, Sinks.Many<String> sink) {
        try {
            JsonNode node = objectMapper.readTree(data);
            String event = node.path("event").asText("");
            log.info("[Dify] event={}, data={}", event, data.substring(0, Math.min(data.length(), 200)));

            switch (event) {
                case "agent_thought" -> emitAgentThought(node, sink);
                case "agent_message" -> emitText(node.path("answer").asText(""), sessionId, sink);
                case "text_chunk" -> emitText(node.path("data").path("text").asText(""), sessionId, sink);
                case "message" -> emitText(node.path("answer").asText(""), sessionId, sink);
                case "message_end" -> {
                    String convId = node.path("conversation_id").asText("");
                    if (!convId.isEmpty()) {
                        conversationMap.put(sessionId, convId);
                        log.info("[Dify] conversation_id saved: session={}, convId={}", sessionId, convId);
                    }
                    sink.tryEmitNext(toSseData("{\"type\":\"done\"}"));
                    bufferMap.remove(sessionId);
                }
                case "node_started" -> {
                    String nodeType = node.path("data").path("node_type").asText("");
                    String title = node.path("data").path("title").asText("");
                    if ("agent".equals(nodeType)) {
                        sink.tryEmitNext(toSseData(toJson(Map.of(
                                "type", "thinking",
                                "content", "正在调用智能助手 [" + title + "] ..."
                        ))));
                    }
                }
                case "node_finished" -> {
                    String nodeType = node.path("data").path("node_type").asText("");
                    if ("agent".equals(nodeType)) {
                        // Agent 节点完成，可能包含工具调用信息
                        log.info("[Dify] Agent node finished");
                    }
                }
                case "workflow_finished" -> {
                    sink.tryEmitNext(toSseData("{\"type\":\"done\"}"));
                    bufferMap.remove(sessionId);
                }
                // workflow_started, ping 等忽略
                default -> log.info("[Dify] Ignored event: {}", event);
            }
        } catch (Exception e) {
            log.warn("[Dify] Parse error for data: {}", data.substring(0, Math.min(data.length(), 80)), e);
        }
    }

    // ==================== 事件发射 ====================

    private void emitAgentThought(JsonNode node, Sinks.Many<String> sink) {
        String thought = node.path("thought").asText("").trim();
        String tool = node.path("tool").asText("").trim();
        String toolInput = node.path("tool_input").asText("").trim();
        String observation = node.path("observation").asText("").trim();

        if (!thought.isEmpty()) {
            sink.tryEmitNext(toSseData(toJson(Map.of("type", "thinking", "content", thought))));
        }

        if (!tool.isEmpty() && !toolInput.isEmpty() && observation.isEmpty()) {
            sink.tryEmitNext(toSseData(toJson(Map.of(
                    "type", "tool_call",
                    "tool", tool,
                    "input", formatToolInput(toolInput)
            ))));
        }

        if (!observation.isEmpty()) {
            boolean isBlocked = observation.contains("BLOCKED_BY_RISK");
            sink.tryEmitNext(toSseData(toJson(Map.of(
                    "type", "tool_result",
                    "tool", tool,
                    "status", isBlocked ? "BLOCKED_BY_RISK" : "SUCCESS",
                    "content", extractObservation(observation)
            ))));
        }
    }

    // 跟踪每个 session 是否正处于 <think> 块内
    private final Map<String, Boolean> thinkStateMap = new ConcurrentHashMap<>();

    private void emitText(String text, String sessionId, Sinks.Many<String> sink) {
        if (text == null || text.isEmpty()) return;

        boolean inThink = thinkStateMap.getOrDefault(sessionId, false);
        int idx = 0;

        while (idx < text.length()) {
            if (!inThink) {
                int thinkStart = text.indexOf("<think>", idx);
                if (thinkStart == -1) {
                    // 无更多 think 标签，剩余部分作为正常文本
                    String remaining = text.substring(idx).trim();
                    if (!remaining.isEmpty()) {
                        sink.tryEmitNext(toSseData(toJson(Map.of("type", "text", "content", remaining))));
                    }
                    break;
                } else {
                    // <think> 之前的文本作为正常输出
                    if (thinkStart > idx) {
                        String before = text.substring(idx, thinkStart).trim();
                        if (!before.isEmpty()) {
                            sink.tryEmitNext(toSseData(toJson(Map.of("type", "text", "content", before))));
                        }
                    }
                    idx = thinkStart + 7; // 跳过 "<think>"
                    inThink = true;
                }
            } else {
                int thinkEnd = text.indexOf("</think>", idx);
                if (thinkEnd == -1) {
                    // think 块未闭合，后续 chunk 会继续
                    String thinking = text.substring(idx).trim();
                    if (!thinking.isEmpty()) {
                        sink.tryEmitNext(toSseData(toJson(Map.of("type", "thinking", "content", thinking))));
                    }
                    break;
                } else {
                    // think 块完整
                    String thinking = text.substring(idx, thinkEnd).trim();
                    if (!thinking.isEmpty()) {
                        sink.tryEmitNext(toSseData(toJson(Map.of("type", "thinking", "content", thinking))));
                    }
                    idx = thinkEnd + 8; // 跳过 "</think>"
                    inThink = false;
                }
            }
        }

        thinkStateMap.put(sessionId, inThink);
    }

    // ==================== 工具方法 ====================

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
            if (node.has("observation")) return node.path("observation").asText();
            return observation.length() > 300 ? observation.substring(0, 300) + "..." : observation;
        } catch (Exception e) {
            return observation.length() > 300 ? observation.substring(0, 300) + "..." : observation;
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
        return json;
    }
}
