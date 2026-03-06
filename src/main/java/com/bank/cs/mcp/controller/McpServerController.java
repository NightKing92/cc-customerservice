package com.bank.cs.mcp.controller;

import com.bank.cs.mcp.service.BankToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * MCP Server 端点（Streamable HTTP Transport）
 *
 * 实现 Model Context Protocol 的 JSON-RPC 协议层，供 Dify Agent 节点调用。
 * 端点：POST /mcp
 *
 * 支持的方法：
 *   - initialize        : 握手，返回服务器能力声明
 *   - tools/list        : 返回所有可用工具定义
 *   - tools/call        : 执行指定工具
 *   - notifications/*   : 客户端通知，无需响应
 */
@RestController
@RequestMapping("/mcp")
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    private final BankToolService bankToolService;
    private final ObjectMapper objectMapper;

    // 工具定义列表（tools/list 响应）
    private static final List<Map<String, Object>> TOOL_DEFINITIONS = List.of(
            buildTool(
                    "analyze_spending_habit",
                    "分析用户指定月份的消费习惯，返回餐饮、娱乐、交通、购物等类别的消费占比和金额。当客户询问消费情况、花费分析、账单明细时调用。",
                    Map.of(
                            "userId", param("string", "用户ID"),
                            "month", param("string", "查询月份，格式YYYY-MM，例如2025-01")
                    ),
                    List.of("userId", "month")
            ),
            buildTool(
                    "check_wealth_purchasing_power",
                    "检查用户账户余额是否足够购买指定金额的理财产品，并验证风险测评是否在有效期内。客户询问能否购买黄金、基金等产品时调用。",
                    Map.of(
                            "userId", param("string", "用户ID"),
                            "requiredAmount", param("number", "所需金额（元）"),
                            "riskType", param("string", "产品风险类型，如黄金、基金、股票")
                    ),
                    List.of("userId", "requiredAmount", "riskType")
            ),
            buildTool(
                    "generate_and_email_asset_proof",
                    "生成资产证明文件并发送到客户指定邮箱。属于高危敏感操作，需要人脸识别验证。客户要求开具资产证明并发送邮件时调用。",
                    Map.of(
                            "userId", param("string", "用户ID"),
                            "emailAddress", param("string", "收件邮箱地址")
                    ),
                    List.of("userId", "emailAddress")
            )
    );

    public McpServerController(BankToolService bankToolService, ObjectMapper objectMapper) {
        this.bankToolService = bankToolService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handleMcp(
            @RequestBody Map<String, Object> request) {

        String method = (String) request.get("method");
        Object id = request.get("id");
        log.info("[MCP] method={}, id={}", method, id);

        // notifications/* 是单向通知，不需要响应体
        if (method != null && method.startsWith("notifications/")) {
            return ResponseEntity.ok(Map.of());
        }

        try {
            Map<String, Object> result = switch (method != null ? method : "") {
                case "initialize" -> buildInitializeResult();
                case "tools/list" -> buildToolsListResult();
                case "tools/call" -> dispatchToolCall(request);
                // MCP 协议要求这些方法返回空列表，不能返回 error
                case "resources/list" -> Map.of("resources", List.of());
                case "resources/templates/list" -> Map.of("resourceTemplates", List.of());
                case "prompts/list" -> Map.of("prompts", List.of());
                default -> null;
            };

            if (result == null) {
                return ResponseEntity.ok(jsonRpcError(id, -32601, "Method not found: " + method));
            }
            return ResponseEntity.ok(jsonRpcSuccess(id, result));

        } catch (Exception e) {
            log.error("[MCP] Internal error for method={}", method, e);
            return ResponseEntity.ok(jsonRpcError(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    // ==================== MCP 方法实现 ====================

    private Map<String, Object> buildInitializeResult() {
        return Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "bank-cs-mcp", "version", "1.0.0")
        );
    }

    private Map<String, Object> buildToolsListResult() {
        return Map.of("tools", TOOL_DEFINITIONS);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatchToolCall(Map<String, Object> request) throws Exception {
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        String toolName = (String) params.get("name");
        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        log.info("[MCP] tool={}, args={}", toolName, args);

        Map<String, Object> toolResult = switch (toolName) {
            case "analyze_spending_habit" -> bankToolService.analyzeSpendingHabit(
                    str(args, "userId"),
                    str(args, "month")
            );
            case "check_wealth_purchasing_power" -> bankToolService.checkWealthPurchasingPower(
                    str(args, "userId"),
                    new BigDecimal(args.get("requiredAmount").toString()),
                    str(args, "riskType")
            );
            case "generate_and_email_asset_proof" -> bankToolService.generateAndEmailAssetProof(
                    str(args, "userId"),
                    str(args, "emailAddress")
            );
            default -> Map.of("status", "ERROR", "observation", "Unknown tool: " + toolName);
        };

        // MCP tools/call 响应格式：content 数组
        String resultJson = objectMapper.writeValueAsString(toolResult);
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", resultJson)),
                "isError", false
        );
    }

    // ==================== JSON-RPC 工具方法 ====================

    private static Map<String, Object> jsonRpcSuccess(Object id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("result", result);
        if (id != null) {
            response.put("id", id);
        }
        return response;
    }

    private static Map<String, Object> jsonRpcError(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("error", Map.of("code", code, "message", message));
        if (id != null) {
            response.put("id", id);
        }
        return response;
    }

    // ==================== 工具定义构建工具方法 ====================

    private static Map<String, Object> buildTool(String name, String description,
                                                  Map<String, Object> properties,
                                                  List<String> required) {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", required);

        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", inputSchema
        );
    }

    private static Map<String, Object> param(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}
