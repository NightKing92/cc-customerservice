package com.bank.cs.service;

import com.bank.cs.model.ChatMessage;
import com.bank.cs.model.LlmParsedResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;

@Service
public class DeepSeekService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final IntentRegistry intentRegistry;

    public DeepSeekService(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String model,
            IntentRegistry intentRegistry) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        this.objectMapper = new ObjectMapper();
        this.model = model;
        this.intentRegistry = intentRegistry;
    }

    /**
     * 意图识别 + 槽位提取（非流式，要求JSON输出）
     */
    public LlmParsedResult parseIntent(String userInput, List<ChatMessage> history, String currentContext) {
        String systemPrompt = buildIntentSystemPrompt(currentContext);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 加入最近对话历史
        for (ChatMessage msg : history) {
            String role = msg.getRole() == ChatMessage.Role.USER ? "user" : "assistant";
            messages.add(Map.of("role", role, "content", msg.getContent()));
        }
        messages.add(Map.of("role", "user", "content", userInput));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.1);
        body.put("max_tokens", 500);
        body.put("response_format", Map.of("type", "json_object"));

        try {
            String response = webClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            log.info("Intent parse result: {}", content);

            return objectMapper.readValue(content, LlmParsedResult.class);
        } catch (Exception e) {
            log.error("Failed to parse intent", e);
            // fallback: 当作问答
            LlmParsedResult fallback = new LlmParsedResult();
            fallback.setIntent("qa");
            fallback.setNeedWebSearch(false);
            return fallback;
        }
    }

    /**
     * 流式生成回复（用于问答场景）
     */
    public Flux<String> streamChat(String userInput, List<ChatMessage> history, String systemContext) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "你是一个银行智能客服助手，回答用户的问题。简洁、专业、友好。" +
                (systemContext != null ? "\n附加上下文:\n" + systemContext : "")));

        for (ChatMessage msg : history) {
            String role = msg.getRole() == ChatMessage.Role.USER ? "user" : "assistant";
            messages.add(Map.of("role", role, "content", msg.getContent()));
        }
        messages.add(Map.of("role", "user", "content", userInput));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.7);
        body.put("max_tokens", 1000);
        body.put("stream", true);

        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(data -> !data.equals("[DONE]") && !data.isBlank())
                .mapNotNull(data -> {
                    try {
                        JsonNode node = objectMapper.readTree(data);
                        JsonNode delta = node.path("choices").path(0).path("delta").path("content");
                        return delta.isMissingNode() ? null : delta.asText();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull);
    }

    /**
     * 联网搜索模拟（使用DeepSeek生成搜索结果摘要）
     */
    public Flux<String> streamWithSearchContext(String userInput, String searchQuery,
                                                List<ChatMessage> history) {
        String searchContext = "用户问题需要最新信息。搜索关键词: \"" + searchQuery +
                "\"\n请根据你的知识尽可能回答，如果不确定请说明。";
        return streamChat(userInput, history, searchContext);
    }

    private String buildIntentSystemPrompt(String currentContext) {
        return """
                你是一个银行智能客服的意图识别引擎。根据用户输入，判断用户意图并提取槽位信息。

                %s

                %s

                你必须返回严格的JSON格式，schema如下:
                {
                  "intent": "transfer|credit_card|qa|none",
                  "slots": {"payee": "值或null", "amount": "值或null", "card_type": "值或null"},
                  "qaAnswer": "如果是qa意图，给出简短回答；否则null",
                  "needWebSearch": false,
                  "searchQuery": "需要搜索时的关键词，否则null"
                }

                规则:
                1. 如果用户明确提到转账/汇款/打钱，intent=transfer，并尽量提取payee和amount
                2. 如果用户明确提到办信用卡/申请信用卡，intent=credit_card
                3. 如果用户在问问题或闲聊，intent=qa
                4. 只有当用户在直接回答当前业务的槽位提问(如回答人名、金额、卡片类型)时，intent=none，并在slots中填入对应值
                5. 如果问题涉及实时信息(汇率、股价、新闻等)，设needWebSearch=true并给出searchQuery
                6. amount只提取数字部分，如"500元"提取"500"
                7. 关键：即使当前有活跃业务，如果用户明确表达了要办理另一项业务，必须识别为对应的业务intent，不要错误地设为none
                """.formatted(intentRegistry.buildIntentDescriptions(),
                currentContext != null ? "当前对话状态:\n" + currentContext : "当前无活跃业务");
    }
}
