package com.zeroai.ai.annotation;

import com.zeroai.ai.config.AiAgentAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 开启 AI Agent 通用能力。
 * <p>
 * 业务项目只需引入 {@code zeroai-ai-starter} 依赖并加上本注解（或依赖自动装配），
 * 再通过 SPI 注册工具即可接入，无需关心 ChatClient 装配、Prompt 加载与 SSE 通道。
 * <p>
 * 注意：本注解只装配通用能力，不包含任何业务工具；业务能力必须由使用方显式注册，
 * 以保证 AI 模块永远不反向依赖业务模块。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(AiAgentAutoConfiguration.class)
public @interface EnableAiAgent {
}
