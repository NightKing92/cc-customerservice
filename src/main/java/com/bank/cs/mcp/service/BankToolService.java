package com.bank.cs.mcp.service;

import com.bank.cs.mcp.annotation.RiskCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 银行 MCP 工具业务实现
 *
 * 每个方法对应一个向 Dify LLM 暴露的工具能力。
 * 风控注解由 RiskControlAspect 统一拦截，业务方法本身只关注正常执行逻辑。
 */
@Service
public class BankToolService {

    private static final Logger log = LoggerFactory.getLogger(BankToolService.class);

    /**
     * 场景1：消费习惯分析
     * 聚合用户指定月份的消费数据，返回各类别占比，由 Dify LLM 生成有温度的理财建议。
     */
    public Map<String, Object> analyzeSpendingHabit(String userId, String month) {
        log.info("[Tool] analyzeSpendingHabit: userId={}, month={}", userId, month);

        // Demo: 模拟查询核心账务库，返回消费分类聚合数据
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("dining", Map.of("amount", 2982.50, "percent", "35%", "label", "餐饮"));
        breakdown.put("entertainment", Map.of("amount", 1704.00, "percent", "20%", "label", "娱乐"));
        breakdown.put("transport", Map.of("amount", 1278.00, "percent", "15%", "label", "交通"));
        breakdown.put("shopping", Map.of("amount", 2556.00, "percent", "30%", "label", "购物"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("userId", userId);
        result.put("month", month);
        result.put("totalAmount", 8520.50);
        result.put("breakdown", breakdown);
        result.put("topCategory", "餐饮");
        result.put("observation", "本月总消费¥8520.50，餐饮占比最高（35%），共消费¥2982.50");
        return result;
    }

    /**
     * 场景2：理财购买力评估
     * 检查账户余额是否充足，同时验证风险测评是否在有效期内。
     * 若风险测评过期，即使余额充足也必须拦截交易。
     */
    public Map<String, Object> checkWealthPurchasingPower(
            String userId, BigDecimal requiredAmount, String riskType) {

        log.info("[Tool] checkWealthPurchasingPower: userId={}, amount={}, riskType={}",
                userId, requiredAmount, riskType);

        // Demo: 模拟查询账户余额
        BigDecimal balance = new BigDecimal("125000.00");

        // Demo: 模拟风险测评已过期（超过1年）
        boolean riskAssessmentExpired = true;

        // 高风险产品（黄金、基金、股票等）必须有效风险测评
        if (riskAssessmentExpired && isHighRiskProduct(riskType)) {
            return Map.of(
                    "status", "BLOCKED_BY_RISK",
                    "observation", String.format(
                            "您的账户余额充足（¥%s），但您的风险测评已超过1年未更新，" +
                            "根据监管要求当前禁止购买%s类产品。" +
                            "请前往银行App完成风险承受能力评估后即可继续交易。",
                            balance.toPlainString(), riskType)
            );
        }

        boolean sufficient = balance.compareTo(requiredAmount) >= 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("balance", balance);
        result.put("requiredAmount", requiredAmount);
        result.put("sufficient", sufficient);
        result.put("riskType", riskType);
        result.put("observation", sufficient
                ? String.format("您的账户余额¥%s，购买%s所需¥%s，余额充足，可以购买。",
                        balance.toPlainString(), riskType, requiredAmount.toPlainString())
                : String.format("您的账户余额¥%s，购买%s所需¥%s，余额不足，缺口¥%s。",
                        balance.toPlainString(), riskType, requiredAmount.toPlainString(),
                        requiredAmount.subtract(balance).toPlainString())
        );
        return result;
    }

    /**
     * 场景3：资产证明邮件（高危操作）
     * 涉及文件生成和对外发送，必须通过人脸识别才能执行。
     * 风控前置由 @RiskCheck 注解 + AOP 拦截器完成。
     */
    @RiskCheck(
            requiredVerifications = {"FACE_AUTH"},
            blockMessage = "开具资产证明属于敏感操作，需要先完成身份验证。" +
                           "请打开银行App，进入「我的」→「身份验证」，完成人脸识别后即可继续。"
    )
    public Map<String, Object> generateAndEmailAssetProof(String userId, String emailAddress) {
        log.info("[Tool] generateAndEmailAssetProof: userId={}, email={}", userId, emailAddress);

        // AOP 通过后才会执行到这里
        String documentId = "PROOF-" + userId + "-" + System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SUCCESS");
        result.put("documentId", documentId);
        result.put("emailAddress", emailAddress);
        result.put("observation", String.format(
                "资产证明（编号：%s）已生成，正在发送至%s，预计3分钟内送达，请注意查收。",
                documentId, emailAddress));
        return result;
    }

    private boolean isHighRiskProduct(String riskType) {
        if (riskType == null) {
            return false;
        }
        String lower = riskType.toLowerCase();
        return lower.contains("黄金") || lower.contains("基金")
                || lower.contains("股票") || lower.contains("期货")
                || lower.contains("gold") || lower.contains("fund");
    }
}
