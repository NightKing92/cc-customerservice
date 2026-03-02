package com.bank.cs.controller;

import com.bank.cs.model.ChatMessage;
import com.bank.cs.model.SessionContext;
import com.bank.cs.service.DialogEngine;
import com.bank.cs.service.SessionManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final DialogEngine dialogEngine;
    private final SessionManager sessionManager;

    public ChatController(DialogEngine dialogEngine, SessionManager sessionManager) {
        this.dialogEngine = dialogEngine;
        this.sessionManager = sessionManager;
    }

    /**
     * SSE流式聊天接口
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam String sessionId,
            @RequestParam String message) {
        return dialogEngine.process(sessionId, message);
    }

    /**
     * 非流式聊天接口（兜底）
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> req) {
        String sessionId = req.getOrDefault("sessionId", "");
        String message = req.getOrDefault("message", "");

        List<String> chunks = new ArrayList<>();
        dialogEngine.process(sessionId, message)
                .doOnNext(chunks::add)
                .blockLast();

        String fullResponse = String.join("", chunks);
        SessionContext ctx = sessionManager.getOrCreate(sessionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", ctx.getSessionId());
        result.put("response", fullResponse);
        result.put("hasActiveTask", ctx.hasActiveFrame());
        if (ctx.hasActiveFrame()) {
            result.put("currentIntent", ctx.currentFrame().getIntentName());
            result.put("collectedSlots", ctx.currentFrame().getCollectedSlots());
        }
        return result;
    }

    /**
     * 获取会话状态（调试用）
     */
    @GetMapping("/session/{sessionId}")
    public Map<String, Object> getSession(@PathVariable String sessionId) {
        SessionContext ctx = sessionManager.get(sessionId);
        if (ctx == null) {
            return Map.of("error", "Session not found");
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionId", sessionId);
        info.put("messageCount", ctx.getChatHistory().size());
        info.put("stackDepth", ctx.getDialogStack().size());
        info.put("hasActiveFrame", ctx.hasActiveFrame());
        if (ctx.hasActiveFrame()) {
            var frame = ctx.currentFrame();
            info.put("currentFrame", Map.of(
                    "intent", frame.getIntentId(),
                    "state", frame.getState().name(),
                    "collectedSlots", frame.getCollectedSlots(),
                    "missingSlots", frame.getMissingRequiredSlots().stream()
                            .map(s -> s.getName()).toList()
            ));
        }

        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage msg : ctx.getChatHistory()) {
            history.add(Map.of("role", msg.getRole().name(), "content", msg.getContent()));
        }
        info.put("history", history);
        return info;
    }

    /**
     * 创建新会话
     */
    @PostMapping("/session/new")
    public Map<String, String> newSession() {
        SessionContext ctx = sessionManager.getOrCreate(null);
        return Map.of("sessionId", ctx.getSessionId());
    }
}
