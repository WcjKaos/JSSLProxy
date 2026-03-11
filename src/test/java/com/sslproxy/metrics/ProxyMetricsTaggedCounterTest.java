package com.sslproxy.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProxyMetrics 分类 Counter 单元测试（T-03 / T-04）
 */
class ProxyMetricsTaggedCounterTest {

    private SimpleMeterRegistry registry;
    private ProxyMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ProxyMetrics(registry, null);
    }

    // ========== SSL 握手失败分类 ==========

    @Test
    void should_incrementExpiredCounter_when_reasonIsExpired() {
        metrics.incrementSslHandshakeFailures("expired");

        double count = registry.counter("proxy.ssl.handshakes.failures", "reason", "expired").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_incrementOtherCounter_when_reasonIsUnknown() {
        metrics.incrementSslHandshakeFailures("not_a_real_reason");

        double count = registry.counter("proxy.ssl.handshakes.failures", "reason", "other").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_incrementOtherCounter_when_reasonIsNull() {
        metrics.incrementSslHandshakeFailures((String) null);

        double count = registry.counter("proxy.ssl.handshakes.failures", "reason", "other").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_incrementOtherCounter_when_noArgOverload() {
        metrics.incrementSslHandshakeFailures();

        double count = registry.counter("proxy.ssl.handshakes.failures", "reason", "other").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_countIndependently_across_all_ssl_reasons() {
        for (String reason : ProxyMetrics.SSL_FAILURE_REASONS) {
            metrics.incrementSslHandshakeFailures(reason);
        }

        for (String reason : ProxyMetrics.SSL_FAILURE_REASONS) {
            double count = registry.counter("proxy.ssl.handshakes.failures", "reason", reason).count();
            assertEquals(1.0, count, "Expected count=1 for reason=" + reason);
        }
    }

    // ========== 后端连接错误分类 ==========

    @Test
    void should_incrementRefusedCounter_when_causeIsRefused() {
        metrics.incrementConnectionErrors("refused");

        double count = registry.counter("proxy.connections.errors", "cause", "refused").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_incrementCircuitOpenCounter_when_causeIsCircuitOpen() {
        metrics.incrementConnectionErrors("circuit_open");

        double count = registry.counter("proxy.connections.errors", "cause", "circuit_open").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_incrementOtherCounter_when_causeIsUnknown() {
        metrics.incrementConnectionErrors("unexpected_cause");

        double count = registry.counter("proxy.connections.errors", "cause", "other").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_incrementOtherCounter_when_causeIsNull() {
        metrics.incrementConnectionErrors((String) null);

        double count = registry.counter("proxy.connections.errors", "cause", "other").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_incrementOtherCounter_when_noArgConnectionErrorOverload() {
        metrics.incrementConnectionErrors();

        double count = registry.counter("proxy.connections.errors", "cause", "other").count();
        assertEquals(1.0, count);
    }

    @Test
    void should_countIndependently_across_all_connection_causes() {
        for (String cause : ProxyMetrics.CONNECTION_ERROR_CAUSES) {
            metrics.incrementConnectionErrors(cause);
        }

        for (String cause : ProxyMetrics.CONNECTION_ERROR_CAUSES) {
            double count = registry.counter("proxy.connections.errors", "cause", cause).count();
            assertEquals(1.0, count, "Expected count=1 for cause=" + cause);
        }
    }
}
