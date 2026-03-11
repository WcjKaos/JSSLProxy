package com.sslproxy.resilience;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig.CircuitBreaker;
import com.sslproxy.metrics.MetricsRegistry;
import com.sslproxy.metrics.ProxyMetrics;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;

/**
 * 熔断器实现（基于 Resilience4j）
 * <p>
 * 使用 Resilience4j 库实现的工业级熔断器，支持三种状态：
 * <ul>
 *   <li>CLOSED：正常状态，请求正常通过</li>
 *   <li>OPEN：熔断状态，拒绝所有请求</li>
 *   <li>HALF_OPEN：半开状态，允许少量测试请求</li>
 * </ul>
 * </p>
 * <p>
 * 优势：
 * <ul>
 *   <li>Ring Bit Buffer 实现，内存固定且极低</li>
 *   <li>Micrometer 自动集成，暴露 Prometheus 指标</li>
 *   <li>事件驱动，支持状态变更监听</li>
 * </ul>
 * </p>
 *
 * @author SSL Proxy Team
 * @version 2.0
 */
public class BackendCircuitBreaker {
    private static final Logger logger = LoggerFactory.getLogger(BackendCircuitBreaker.class);

    private static final String CIRCUIT_BREAKER_NAME = "backend-proxy";

    private final MetricsRegistry metricsRegistryRef;
    private final ProxyMetrics metricsRef;
    private final AuditLogger auditLoggerRef;

    /**
     * 熔断器状态（保持与原 API 兼容）
     */
    public enum State {
        CLOSED,     // 正常状态
        OPEN,       // 熔断状态
        HALF_OPEN   // 半开状态
    }

    private final CircuitBreaker config;
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;
    private final CircuitBreakerRegistry registry;

    // 统计计数器（保持与原 API 兼容）
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong successfulCalls = new AtomicLong(0);
    private final AtomicLong failedCalls = new AtomicLong(0);
    private final AtomicLong rejectedCalls = new AtomicLong(0);
    private final AtomicLong stateTransitions = new AtomicLong(0);

    /**
     * 构造函数
     *
     * @param config          熔断器配置
     * @param metricsRegistry 指标注册中心
     * @param metrics         代理指标
     * @param auditLogger     审计日志记录器
     */
    public BackendCircuitBreaker(CircuitBreaker config, MetricsRegistry metricsRegistry,
                                  ProxyMetrics metrics, AuditLogger auditLogger) {
        this.config = config;
        this.metricsRegistryRef = metricsRegistry;
        this.metricsRef = metrics;
        this.auditLoggerRef = auditLogger;

        // 构建 Resilience4j 配置
        CircuitBreakerConfig resilience4jConfig = CircuitBreakerConfig.custom()
                // 失败率阈值（百分比）
                .failureRateThreshold(config.failureRateThreshold)
                // 慢调用率阈值（百分比）
                .slowCallRateThreshold(config.slowCallRateThreshold)
                // 慢调用定义阈值
                .slowCallDurationThreshold(Duration.ofMillis(config.slowCallDurationThreshold))
                // 滑动窗口类型：基于计数
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                // 滑动窗口大小
                .slidingWindowSize(config.slidingWindowSize)
                // 最小调用次数
                .minimumNumberOfCalls(config.minimumNumberOfCalls)
                // OPEN 状态等待时间
                .waitDurationInOpenState(Duration.ofSeconds(config.waitDurationInOpenState))
                // HALF_OPEN 状态允许的调用次数
                .permittedNumberOfCallsInHalfOpenState(config.permittedNumberOfCallsInHalfOpenState)
                // 自动从 HALF_OPEN 转换（默认启用）
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        // 创建注册表和熔断器
        this.registry = CircuitBreakerRegistry.of(resilience4jConfig);
        this.circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);

        // 注册状态变更事件监听器
        registerEventListeners();

        // 绑定 Micrometer 指标（如果指标系统已初始化）
        bindMicrometerMetrics();

        logger.info("BackendCircuitBreaker initialized with Resilience4j: " +
                "failureRateThreshold={}%, slowCallRateThreshold={}%, slidingWindowSize={}, waitDuration={}s",
                config.failureRateThreshold, config.slowCallRateThreshold,
                config.slidingWindowSize, config.waitDurationInOpenState);
    }

    /**
     * 注册事件监听器
     */
    private void registerEventListeners() {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    String fromState = event.getStateTransition().getFromState().name();
                    String toState = event.getStateTransition().getToState().name();

                    stateTransitions.incrementAndGet();

                    // 更新 ProxyMetrics 中的状态
                    updateMetricsState(mapState(event.getStateTransition().getToState()));

                    // 计算当前失败率
                    float failureRate = circuitBreaker.getMetrics().getFailureRate();

                    // 记录审计日志
                    auditLoggerRef.logCircuitBreakerStateChange(
                            fromState, toState, failureRate);

                    if (toState.equals("OPEN")) {
                        logger.warn("Circuit breaker transitioning from {} to {}: failureRate={}%",
                                fromState, toState, String.format("%.2f", failureRate));
                    } else {
                        logger.info("Circuit breaker transitioning from {} to {}", fromState, toState);
                    }
                })
                .onCallNotPermitted(event -> {
                    rejectedCalls.incrementAndGet();
                    incrementMetricsRejected();
                    logger.debug("Circuit breaker rejected call");
                });
    }

    /**
     * 绑定 Micrometer 指标
     */
    private void bindMicrometerMetrics() {
        try {
            if (metricsRegistryRef != null && metricsRegistryRef.isEnabled()) {
                TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)
                        .bindTo(metricsRegistryRef.getRegistry());
                logger.info("Resilience4j circuit breaker metrics bound to Micrometer");
            }
        } catch (IllegalStateException e) {
            // MetricsRegistry 未初始化，跳过指标绑定（测试场景或指标未启用）
            logger.debug("Skipping Micrometer metrics binding: {}", e.getMessage());
        } catch (Exception e) {
            logger.warn("Failed to bind circuit breaker metrics to Micrometer: {}", e.getMessage());
        }
    }

    /**
     * 检查是否允许请求通过
     *
     * @return true 如果允许请求，false 如果被熔断
     */
    public boolean allowRequest() {
        if (config == null || !config.enabled) {
            return true;
        }

        try {
            // 尝试获取许可
            circuitBreaker.acquirePermission();
            return true;
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // 熔断器拒绝请求
            return false;
        }
    }

    /**
     * 记录成功的调用
     *
     * @param duration 调用耗时（毫秒）
     */
    public void recordSuccess(long duration) {
        if (config == null || !config.enabled) {
            return;
        }

        totalCalls.incrementAndGet();
        successfulCalls.incrementAndGet();
        incrementMetricsSuccessful();

        // 通知 Resilience4j 调用成功
        circuitBreaker.onSuccess(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 记录失败的调用
     *
     * @param duration 调用耗时（毫秒）
     */
    public void recordFailure(long duration) {
        if (config == null || !config.enabled) {
            return;
        }

        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        incrementMetricsFailed();

        // 通知 Resilience4j 调用失败
        circuitBreaker.onError(duration, java.util.concurrent.TimeUnit.MILLISECONDS,
                new RuntimeException("Backend call failed"));
    }

    /**
     * 映射 Resilience4j 状态到本地状态
     */
    private State mapState(io.github.resilience4j.circuitbreaker.CircuitBreaker.State r4jState) {
        return switch (r4jState) {
            case CLOSED -> State.CLOSED;
            case OPEN -> State.OPEN;
            case HALF_OPEN -> State.HALF_OPEN;
            case DISABLED, FORCED_OPEN, METRICS_ONLY -> State.CLOSED; // 特殊状态映射为 CLOSED
        };
    }

    /**
     * 更新指标中的熔断器状态
     */
    private void updateMetricsState(State newState) {
        ProxyMetrics metrics = metricsRef;
        int stateValue = switch (newState) {
            case CLOSED -> 0;
            case OPEN -> 1;
            case HALF_OPEN -> 2;
        };
        metrics.setCircuitBreakerState(stateValue);
    }

    /**
     * 增加成功调用指标
     */
    private void incrementMetricsSuccessful() {
        metricsRef.incrementCircuitBreakerSuccessful();
    }

    /**
     * 增加失败调用指标
     */
    private void incrementMetricsFailed() {
        metricsRef.incrementCircuitBreakerFailed();
    }

    /**
     * 增加被拒绝调用指标
     */
    private void incrementMetricsRejected() {
        metricsRef.incrementCircuitBreakerRejected();
    }

    /**
     * 获取当前状态
     */
    public State getState() {
        if (circuitBreaker == null) {
            return State.CLOSED;
        }
        return mapState(circuitBreaker.getState());
    }

    /**
     * 获取统计信息
     */
    public CircuitBreakerStats getStats() {
        float failureRate = circuitBreaker != null ?
                circuitBreaker.getMetrics().getFailureRate() : 0;

        // 如果没有足够的调用，failureRate 返回 -1
        if (failureRate < 0) {
            failureRate = 0;
        }

        return new CircuitBreakerStats(
                getState(),
                totalCalls.get(),
                successfulCalls.get(),
                failedCalls.get(),
                rejectedCalls.get(),
                failureRate,
                stateTransitions.get()
        );
    }

    /**
     * 手动重置熔断器
     */
    public void reset() {
        if (circuitBreaker != null) {
            circuitBreaker.reset();
        }
        logger.info("Circuit breaker manually reset to CLOSED state");
    }

    /**
     * 获取 Resilience4j 熔断器实例（用于高级操作）
     *
     * @return Resilience4j CircuitBreaker 实例
     */
    public io.github.resilience4j.circuitbreaker.CircuitBreaker getResilience4jCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * 熔断器统计信息
     */
    public static class CircuitBreakerStats {
        public final State state;
        public final long totalCalls;
        public final long successfulCalls;
        public final long failedCalls;
        public final long rejectedCalls;
        public final double failureRate;
        public final long stateTransitions;

        public CircuitBreakerStats(State state, long totalCalls, long successfulCalls,
                                   long failedCalls, long rejectedCalls, double failureRate,
                                   long stateTransitions) {
            this.state = state;
            this.totalCalls = totalCalls;
            this.successfulCalls = successfulCalls;
            this.failedCalls = failedCalls;
            this.rejectedCalls = rejectedCalls;
            this.failureRate = failureRate;
            this.stateTransitions = stateTransitions;
        }
    }
}
