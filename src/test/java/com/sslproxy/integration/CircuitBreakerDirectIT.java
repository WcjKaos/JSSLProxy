package com.sslproxy.integration;

import org.junit.jupiter.api.DisplayName;

import com.sslproxy.config.ProxyConfig;

@DisplayName("熔断器响应生命周期集成测试（直连）")
class CircuitBreakerDirectIT extends CircuitBreakerIT {
    @Override
    protected ProxyConfig createProxyConfig() {
        ProxyConfig config = super.createProxyConfig();
        config.performance.backendPool.enabled = false;
        return config;
    }
}
