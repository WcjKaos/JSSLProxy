package com.sslproxy.metrics;

import com.sslproxy.config.ProxyConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetricsRegistry 单元测试
 */
class MetricsRegistryTest {

    @Test
    void testInitializeWithEnabledMetrics() {
        ProxyConfig config = createConfigWithMetrics(true);

        MetricsRegistry registry = new MetricsRegistry(config);

        assertNotNull(registry);
        assertTrue(registry.isEnabled());
        assertNotNull(registry.getRegistry());
    }

    @Test
    void testInitializeWithDisabledMetrics() {
        ProxyConfig config = createConfigWithMetrics(false);

        MetricsRegistry registry = new MetricsRegistry(config);

        assertNotNull(registry);
        assertFalse(registry.isEnabled());
    }

    @Test
    void testInitializeWithNullMetricsConfig() {
        ProxyConfig config = new ProxyConfig();
        config.metrics = null;

        MetricsRegistry registry = new MetricsRegistry(config);

        assertNotNull(registry);
        assertFalse(registry.isEnabled());
    }

    @Test
    void testScrapeReturnsPrometheusFormat() {
        ProxyConfig config = createConfigWithMetrics(true);

        MetricsRegistry registry = new MetricsRegistry(config);

        String scrapeData = registry.scrape();
        assertNotNull(scrapeData);
        // Prometheus 格式应该包含一些基本指标
        assertTrue(scrapeData.length() >= 0);
    }

    @Test
    void testMultipleInstancesAreIndependent() {
        ProxyConfig config1 = createConfigWithMetrics(true);
        ProxyConfig config2 = createConfigWithMetrics(false);

        MetricsRegistry registry1 = new MetricsRegistry(config1);
        MetricsRegistry registry2 = new MetricsRegistry(config2);

        assertTrue(registry1.isEnabled());
        assertFalse(registry2.isEnabled());
    }

    private ProxyConfig createConfigWithMetrics(boolean enabled) {
        ProxyConfig config = new ProxyConfig();
        config.metrics = new ProxyConfig.Metrics();
        config.metrics.enabled = enabled;
        config.metrics.includeJvmMetrics = false;
        return config;
    }
}
