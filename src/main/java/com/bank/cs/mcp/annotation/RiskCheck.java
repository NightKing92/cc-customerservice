package com.bank.cs.mcp.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 风控拦截注解
 * 标记在工具方法上，由 RiskControlAspect 统一执行前置风控校验。
 *
 * @param requiredVerifications 执行前必须通过的验证项，如 FACE_AUTH、RISK_ASSESSMENT
 * @param blockMessage          被拦截时返回给 LLM 的引导话术（observation 字段）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RiskCheck {

    String[] requiredVerifications() default {};

    String blockMessage() default "当前操作需要额外验证，请联系客服。";
}
