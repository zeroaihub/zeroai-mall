package com.zeroai.mobile.framework.config;

import cn.dev33.satoken.config.SaTokenConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobileSaTokenConfigurationTest {

    @Test
    void initSaTokenConfigShouldReadRawAuthorizationHeader() {
        SaTokenConfig saTokenConfig = new SaTokenConfig();
        MobileSaTokenConfiguration configuration = new MobileSaTokenConfiguration(saTokenConfig);

        configuration.initSaTokenConfig();

        assertNull(saTokenConfig.getTokenPrefix());
        assertTrue(saTokenConfig.getIsReadHeader());
    }

    @Test
    void resolveExcludePathsShouldNotOpenMcpWhenDisabled() {
        MobileSaTokenConfiguration configuration =
                new MobileSaTokenConfiguration(new SaTokenConfig());

        List<String> paths = configuration.resolveExcludePaths();

        assertFalse(paths.contains("/mcp"));
    }

    @Test
    void resolveExcludePathsShouldOpenConfiguredMcpEndpointWhenEnabled() {
        MobileSaTokenConfiguration configuration =
                new MobileSaTokenConfiguration(new SaTokenConfig());
        ReflectionTestUtils.setField(configuration, "mcpEnabled", true);
        ReflectionTestUtils.setField(configuration, "mcpEndpoint", "/internal/mcp");

        List<String> paths = configuration.resolveExcludePaths();

        assertTrue(paths.contains("/internal/mcp"));
        assertFalse(paths.contains("/mcp"));
    }
}
