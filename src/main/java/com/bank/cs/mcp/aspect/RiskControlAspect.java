package com.bank.cs.mcp.aspect;

import com.bank.cs.mcp.annotation.RiskCheck;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 全局 MCP 工具风控拦截器
 *
 * 零信任原则：所有工具调用在执行前必须通过风控校验。
 * 拦截结果不抛异常，必须返回 {"status":"BLOCKED_BY_RISK","observation":"..."} 结构，
 * 强制 LLM 读取拦截信息并重新规划引导话术。
 */
@Aspect
@Component
public class RiskControlAspect {

    private static final Logger log = LoggerFactory.getLogger(RiskControlAspect.class);

    @Around("@annotation(riskCheck)")
    public Object checkRisk(ProceedingJoinPoint pjp, RiskCheck riskCheck) throws Throwable {
        String userId = extractUserId(pjp.getArgs());
        log.info("[RiskControl] Checking tool call: method={}, userId={}, verifications={}",
                pjp.getSignature().getName(), userId, riskCheck.requiredVerifications());

        for (String verification : riskCheck.requiredVerifications()) {
            if (!isVerificationPassed(userId, verification)) {
                log.warn("[RiskControl] BLOCKED: user={}, verification={}", userId, verification);
                return Map.of(
                        "status", "BLOCKED_BY_RISK",
                        "observation", riskCheck.blockMessage()
                );
            }
        }

        return pjp.proceed();
    }

    /**
     * 从方法参数中提取 userId（约定第一个 String 参数为 userId）
     */
    private String extractUserId(Object[] args) {
        if (args == null || args.length == 0) {
            return "unknown";
        }
        for (Object arg : args) {
            if (arg instanceof String s && !s.contains("@")) {
                return s;
            }
        }
        return "unknown";
    }

    /**
     * 校验用户是否已完成指定验证项
     * 生产环境：查询 Redis/DB 中的用户验证状态
     * 当前为 Demo 模拟逻辑
     */
    private boolean isVerificationPassed(String userId, String verification) {
        return switch (verification) {
            // 人脸识别：Demo 中模拟未完成，触发拦截以演示风控效果
            case "FACE_AUTH" -> false;
            // 风险测评：由 checkWealthPurchasingPower 内部根据业务逻辑判断
            case "RISK_ASSESSMENT" -> true;
            default -> true;
        };
    }
}
