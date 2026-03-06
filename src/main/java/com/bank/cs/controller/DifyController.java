package com.bank.cs.controller;

import com.bank.cs.service.DifyGatewayService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * Dify 网关接入端点
 * 前端通过此接口与 Dify Chatflow 对话，接收思维链 + 工具调用 + 最终回复的流式事件。
 */
@RestController
@RequestMapping("/api/dify")
public class DifyController {

    private final DifyGatewayService difyGatewayService;

    public DifyController(DifyGatewayService difyGatewayService) {
        this.difyGatewayService = difyGatewayService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam String sessionId,
            @RequestParam String message) {
        return difyGatewayService.chat(sessionId, message);
    }

    @PostMapping("/session/{sessionId}/clear")
    public void clearSession(@PathVariable String sessionId) {
        difyGatewayService.clearSession(sessionId);
    }
}
