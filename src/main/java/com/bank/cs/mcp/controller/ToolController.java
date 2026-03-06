package com.bank.cs.mcp.controller;

import com.bank.cs.mcp.service.BankToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Dify 自定义工具接入端点
 *
 * Dify LLM 在决定调用工具时，通过 HTTP POST 请求这些接口。
 * 所有接口统一返回 HTTP 200，风控拦截信息通过 JSON body 中的 status/observation 传递，
 * 不使用 4xx/5xx，强制 LLM 读取并处理拦截结果。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private static final Logger log = LoggerFactory.getLogger(ToolController.class);

    private final BankToolService bankToolService;

    public ToolController(BankToolService bankToolService) {
        this.bankToolService = bankToolService;
    }

    /**
     * 工具1：消费习惯分析
     * Dify 工具描述：分析用户指定月份的消费习惯，返回各类别消费占比和总额。
     */
    @PostMapping("/analyze-spending-habit")
    public ResponseEntity<Map<String, Object>> analyzeSpendingHabit(
            @RequestBody Map<String, String> params) {

        String userId = params.get("userId");
        String month = params.get("month");
        log.info("[ToolAPI] analyze-spending-habit: userId={}, month={}", userId, month);

        Map<String, Object> result = bankToolService.analyzeSpendingHabit(userId, month);
        return ResponseEntity.ok(result);
    }

    /**
     * 工具2：理财购买力评估
     * Dify 工具描述：检查用户余额是否足够购买指定理财产品，并验证风险测评有效性。
     */
    @PostMapping("/check-wealth-purchasing-power")
    public ResponseEntity<Map<String, Object>> checkWealthPurchasingPower(
            @RequestBody Map<String, Object> params) {

        String userId = (String) params.get("userId");
        BigDecimal requiredAmount = new BigDecimal(params.get("requiredAmount").toString());
        String riskType = (String) params.get("riskType");
        log.info("[ToolAPI] check-wealth-purchasing-power: userId={}, amount={}, type={}",
                userId, requiredAmount, riskType);

        Map<String, Object> result = bankToolService.checkWealthPurchasingPower(
                userId, requiredAmount, riskType);
        return ResponseEntity.ok(result);
    }

    /**
     * 工具3：资产证明邮件（高危操作）
     * Dify 工具描述：生成资产证明文件并发送到客户指定邮箱，需要人脸识别验证。
     */
    @PostMapping("/generate-asset-proof")
    public ResponseEntity<Map<String, Object>> generateAssetProof(
            @RequestBody Map<String, String> params) {

        String userId = params.get("userId");
        String emailAddress = params.get("emailAddress");
        log.info("[ToolAPI] generate-asset-proof: userId={}, email={}", userId, emailAddress);

        Map<String, Object> result = bankToolService.generateAndEmailAssetProof(
                userId, emailAddress);
        return ResponseEntity.ok(result);
    }
}
