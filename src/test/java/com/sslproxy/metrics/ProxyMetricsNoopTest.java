package com.sslproxy.metrics;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ProxyMetricsNoopTest {

    @Test
    void should_neverReturnNull_when_noopCalled() {
        ProxyMetrics metrics = ProxyMetrics.noop();
        assertThat(metrics).isNotNull();
    }

    @Test
    void should_returnFalseForIsEnabled_when_noopInstance() {
        ProxyMetrics metrics = ProxyMetrics.noop();
        assertThat(metrics.isEnabled()).isFalse();
    }

    @Test
    void should_notThrow_when_callingMethodsOnNoopInstance() {
        ProxyMetrics metrics = ProxyMetrics.noop();
        // These should all be safe to call on the NOOP instance
        metrics.incrementActiveConnections();
        metrics.decrementActiveConnections();
        metrics.incrementRequestErrors();
        metrics.incrementGlobalRateLimited();
        metrics.incrementClientRateLimited();
        // No exception = pass
    }
}
