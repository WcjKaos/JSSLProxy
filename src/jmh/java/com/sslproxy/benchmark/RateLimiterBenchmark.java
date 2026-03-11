package com.sslproxy.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.ratelimit.RateLimiterManager;

/**
 * 限流器性能基准测试
 *
 * 测试限流器在高并发场景下的性能
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class RateLimiterBenchmark {

    private RateLimiterManager rateLimiter;

    @Setup(Level.Trial)
    public void setup() {
        // 创建限流器配置
        ProxyConfig.RateLimit config = new ProxyConfig.RateLimit();
        config.enabled = true;
        config.globalRps = 100000;  // 高 RPS 限制，避免影响基准测试
        config.globalBurstCapacity = 10000;
        config.clientRps = 10000;
        config.clientBurstCapacity = 1000;

        rateLimiter = new RateLimiterManager(config);
    }

    /**
     * 测试全局限流检查性能
     */
    @Benchmark
    public void testGlobalRateLimitCheck(Blackhole blackhole) {
        blackhole.consume(rateLimiter.tryConsumeGlobal());
    }

    /**
     * 测试客户端限流检查性能
     */
    @Benchmark
    public void testClientRateLimitCheck(Blackhole blackhole) {
        blackhole.consume(rateLimiter.tryConsumeClient("client-1"));
    }

    /**
     * 测试多客户端限流检查性能
     */
    @Benchmark
    @Threads(4)
    public void testMultiClientRateLimitCheck(Blackhole blackhole, ThreadState state) {
        String clientId = "client-" + (state.counter++ % 100);
        blackhole.consume(rateLimiter.tryConsumeClient(clientId));
    }

    /**
     * 测试完整限流检查（全局 + 客户端）
     */
    @Benchmark
    public void testFullRateLimitCheck(Blackhole blackhole) {
        blackhole.consume(rateLimiter.tryConsume("client-1"));
    }

    /**
     * 测试高并发场景
     */
    @Benchmark
    @Threads(8)
    public void testHighConcurrencyRateLimit(Blackhole blackhole, ThreadState state) {
        String clientId = "client-" + (state.counter++ % 50);
        blackhole.consume(rateLimiter.tryConsume(clientId));
    }

    @State(Scope.Thread)
    public static class ThreadState {
        int counter = 0;
    }
}
