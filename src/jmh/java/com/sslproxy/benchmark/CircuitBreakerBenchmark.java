package com.sslproxy.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.metrics.MetricsRegistry;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.resilience.BackendCircuitBreaker;

/**
 * 熔断器性能基准测试
 *
 * 测试熔断器在高并发场景下的决策性能
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CircuitBreakerBenchmark {

    private BackendCircuitBreaker circuitBreaker;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // 创建熔断器配置
        ProxyConfig.CircuitBreaker config = new ProxyConfig.CircuitBreaker();
        config.enabled = true;
        config.failureRateThreshold = 50;
        config.slidingWindowSize = 100;
        config.minimumNumberOfCalls = 10;
        config.waitDurationInOpenState = 60;
        config.permittedNumberOfCallsInHalfOpenState = 10;
        config.slowCallDurationThreshold = 5000;
        config.slowCallRateThreshold = 100;

        MetricsRegistry metricsRegistry = new MetricsRegistry(new ProxyConfig());
        ProxyMetrics metrics = ProxyMetrics.noop();
        AuditLogger auditLogger = new AuditLogger();

        circuitBreaker = new BackendCircuitBreaker(config, metricsRegistry, metrics, auditLogger);
    }

    /**
     * 测试 allowRequest 决策性能
     */
    @Benchmark
    public void testAllowRequest(Blackhole blackhole) {
        blackhole.consume(circuitBreaker.allowRequest());
    }

    /**
     * 测试记录成功调用性能
     */
    @Benchmark
    public void testRecordSuccess(Blackhole blackhole) {
        circuitBreaker.recordSuccess(50);
        blackhole.consume(circuitBreaker.getState());
    }

    /**
     * 测试记录失败调用性能
     */
    @Benchmark
    public void testRecordFailure(Blackhole blackhole) {
        circuitBreaker.recordFailure(50);
        blackhole.consume(circuitBreaker.getState());
    }

    /**
     * 测试获取统计信息性能
     */
    @Benchmark
    public void testGetStats(Blackhole blackhole) {
        blackhole.consume(circuitBreaker.getStats());
    }

    /**
     * 测试完整请求周期（allowRequest + recordSuccess）
     */
    @Benchmark
    public void testFullRequestCycle(Blackhole blackhole) {
        boolean allowed = circuitBreaker.allowRequest();
        if (allowed) {
            circuitBreaker.recordSuccess(50);
        }
        blackhole.consume(allowed);
    }

    /**
     * 测试混合场景（80% 成功，20% 失败）
     */
    @Benchmark
    @Threads(4)
    public void testMixedScenario(Blackhole blackhole, ThreadState state) {
        boolean allowed = circuitBreaker.allowRequest();
        if (allowed) {
            if (state.counter++ % 5 == 0) {
                circuitBreaker.recordFailure(100);
            } else {
                circuitBreaker.recordSuccess(50);
            }
        }
        blackhole.consume(allowed);
    }

    @State(Scope.Thread)
    public static class ThreadState {
        int counter = 0;
    }
}
