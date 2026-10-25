package com.zeroai.common.config;

import com.zeroai.domain.api.common.ZeroaiConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Zeroai 配置绑定 Bean。
 * 负责把 application.yml 中的 zeroai 配置绑定到契约层静态配置载体，避免 domain-api 自身注册 Spring 组件。
 */
@Component
@ConfigurationProperties(prefix = "zeroai")
public class ZeroaiConfigProperties extends ZeroaiConfig {
}
