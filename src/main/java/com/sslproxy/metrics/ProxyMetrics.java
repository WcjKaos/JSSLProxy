package com.sslproxy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 代理服务器指标定义
 * <p>
 * 定义所有代理相关的 Micrometer 指标，包括：
 * - 连接指标
 * - 请求/响应指标
 * - SSL 握手指标
 * - 限流指标
 * - 错误指标
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ProxyMetrics {
    private static final Logger logger = LoggerFactory.getLogger(ProxyMetrics.class);

    // SSL 握手失败原因分类
    static final List<String> SSL_FAILURE_REASONS = List.of(
            "expired", "revoked", "untrusted", "cipher_mismatch", "protocol_mismatch", "no_cert", "timeout", "other");

    // 后端连接错误原因分类
    static final List<String> CONNECTION_ERROR_CAUSES = List.of(
            "timeout", "refused", "tls", "dns", "pool_exhausted", "circuit_open", "other");

    // NOOP must be declared AFTER the static lists above (constructor references them)
    private static final ProxyMetrics NOOP = new ProxyMetrics(new SimpleMeterRegistry(), null);

    // 连接相关指标
    private final AtomicInteger activeConnections;
    private final Counter totalConnections;
    private final Map<String, Counter> connectionErrorsByCause;

    // 请求相关指标
    private final Counter totalRequests;
    private final Timer requestDuration;
    private final Counter requestErrors;

    // SSL 相关指标
    private final Counter sslHandshakes;
    private final Map<String, Counter> sslHandshakeFailuresByReason;
    private final Timer sslHandshakeDuration;

    // 限流相关指标
    private final Counter rateLimitedRequests;
    private final Counter globalRateLimitedRequests;
    private final Counter clientRateLimitedRequests;

    // 后端连接池指标
    private final AtomicInteger poolActiveConnections;
    private final AtomicInteger poolIdleConnections;
    private final Counter poolAcquireTimeouts;

    // 熔断器相关指标
    private final AtomicInteger circuitBreakerState;
    private final Counter circuitBreakerSuccessful;
    private final Counter circuitBreakerFailed;
    private final Counter circuitBreakerRejected;
    private final Counter circuitBreakerStateTransitions;

    // 证书过期时间指标
    private final AtomicLong serverCertExpirySeconds;
    private final AtomicLong caCertExpirySeconds;

    // 字节传输指标
    private final AtomicLong bytesReceived;
    private final AtomicLong bytesSent;

    // 服务器启动时间
    private final long startTimeMillis;

    /**
     * 构造函数
     *
     * @param registry        MeterRegistry
     * @param histogramBuckets 直方图桶边界
     */
    public ProxyMetrics(MeterRegistry registry, List<Double> histogramBuckets) {
        this.startTimeMillis = System.currentTimeMillis();

        // 活跃连接数
        this.activeConnections = new AtomicInteger(0);
        Gauge.builder("proxy.connections.active", activeConnections, AtomicInteger::get)
                .description("Number of active client connections")
                .register(registry);

        // 总连接数
        this.totalConnections = Counter.builder("proxy.connections.total")
                .description("Total number of client connections")
                .register(registry);

        // 后端连接错误数（按原因分类）
        this.connectionErrorsByCause = new HashMap<>();
        for (String cause : CONNECTION_ERROR_CAUSES) {
            connectionErrorsByCause.put(cause, Counter.builder("proxy.connections.errors")
                    .tag("cause", cause)
                    .description("Backend connection errors by cause")
                    .register(registry));
        }

        // 总请求数
        this.totalRequests = Counter.builder("proxy.requests.total")
                .description("Total number of requests")
                .register(registry);

        // 请求耗时（使用配置的桶边界）
        Timer.Builder durationBuilder = Timer.builder("proxy.request.duration")
                .description("Request processing duration");
        if (histogramBuckets != null && !histogramBuckets.isEmpty()) {
            Duration[] buckets = histogramBuckets.stream()
                    .map(ms -> Duration.ofMillis(ms.longValue()))
                    .toArray(Duration[]::new);
            durationBuilder.serviceLevelObjectives(buckets);
        }
        this.requestDuration = durationBuilder.register(registry);

        // 请求错误数
        this.requestErrors = Counter.builder("proxy.requests.errors")
                .description("Total number of request errors")
                .register(registry);

        // SSL 握手次数
        this.sslHandshakes = Counter.builder("proxy.ssl.handshakes.total")
                .description("Total number of SSL handshakes")
                .register(registry);

        // SSL 握手失败次数（按原因分类）
        this.sslHandshakeFailuresByReason = new HashMap<>();
        for (String reason : SSL_FAILURE_REASONS) {
            sslHandshakeFailuresByReason.put(reason, Counter.builder("proxy.ssl.handshakes.failures")
                    .tag("reason", reason)
                    .description("SSL handshake failures by reason")
                    .register(registry));
        }

        // SSL 握手耗时
        this.sslHandshakeDuration = Timer.builder("proxy.ssl.handshake.duration")
                .description("SSL handshake duration")
                .register(registry);

        // 限流请求总数
        this.rateLimitedRequests = Counter.builder("proxy.ratelimit.total")
                .description("Total number of rate limited requests")
                .register(registry);

        // 全局限流请求数
        this.globalRateLimitedRequests = Counter.builder("proxy.ratelimit.global")
                .description("Number of globally rate limited requests")
                .register(registry);

        // 客户端限流请求数
        this.clientRateLimitedRequests = Counter.builder("proxy.ratelimit.client")
                .description("Number of client rate limited requests")
                .register(registry);

        // 连接池活跃连接数
        this.poolActiveConnections = new AtomicInteger(0);
        Gauge.builder("proxy.pool.active", poolActiveConnections, AtomicInteger::get)
                .description("Number of active connections in pool")
                .register(registry);

        // 连接池空闲连接数
        this.poolIdleConnections = new AtomicInteger(0);
        Gauge.builder("proxy.pool.idle", poolIdleConnections, AtomicInteger::get)
                .description("Number of idle connections in pool")
                .register(registry);

        // 连接池获取超时次数
        this.poolAcquireTimeouts = Counter.builder("proxy.pool.acquire.timeouts")
                .description("Number of connection pool acquire timeouts")
                .register(registry);

        // 熔断器状态 (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
        this.circuitBreakerState = new AtomicInteger(0);
        Gauge.builder("proxy.circuitbreaker.state", circuitBreakerState, AtomicInteger::get)
                .description("Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN)")
                .register(registry);

        // 熔断器成功调用数
        this.circuitBreakerSuccessful = Counter.builder("proxy.circuitbreaker.calls")
                .tag("result", "successful")
                .description("Number of successful calls through circuit breaker")
                .register(registry);

        // 熔断器失败调用数
        this.circuitBreakerFailed = Counter.builder("proxy.circuitbreaker.calls")
                .tag("result", "failed")
                .description("Number of failed calls through circuit breaker")
                .register(registry);

        // 熔断器拒绝调用数
        this.circuitBreakerRejected = Counter.builder("proxy.circuitbreaker.calls")
                .tag("result", "rejected")
                .description("Number of calls rejected by circuit breaker")
                .register(registry);

        // 熔断器状态转换次数
        this.circuitBreakerStateTransitions = Counter.builder("proxy.circuitbreaker.state.transitions")
                .description("Number of circuit breaker state transitions")
                .register(registry);

        // 字节接收
        this.bytesReceived = new AtomicLong(0);
        Gauge.builder("proxy.bytes.received", bytesReceived, AtomicLong::get)
                .description("Total bytes received from clients")
                .baseUnit("bytes")
                .register(registry);

        // 字节发送
        this.bytesSent = new AtomicLong(0);
        Gauge.builder("proxy.bytes.sent", bytesSent, AtomicLong::get)
                .description("Total bytes sent to clients")
                .baseUnit("bytes")
                .register(registry);

        // 服务器运行时间
        Gauge.builder("proxy.uptime.seconds", this, m -> (System.currentTimeMillis() - m.startTimeMillis) / 1000.0)
                .description("Server uptime in seconds")
                .register(registry);

        // 服务端证书过期时间
        this.serverCertExpirySeconds = new AtomicLong(0);
        Gauge.builder("proxy.ssl.cert.expiry.seconds", serverCertExpirySeconds, AtomicLong::get)
                .tag("type", "server")
                .description("Seconds until server certificate expires")
                .baseUnit("seconds")
                .register(registry);

        // CA 证书过期时间
        this.caCertExpirySeconds = new AtomicLong(0);
        Gauge.builder("proxy.ssl.cert.expiry.seconds", caCertExpirySeconds, AtomicLong::get)
                .tag("type", "ca")
                .description("Seconds until CA certificate expires")
                .baseUnit("seconds")
                .register(registry);

        logger.info("ProxyMetrics initialized");
    }

    /**
     * 获取 NoOp 实例（所有方法为空操作）
     */
    public static ProxyMetrics noop() {
        return NOOP;
    }

    /**
     * 是否为真正初始化的实例（非 NoOp）
     */
    public boolean isEnabled() {
        return this != NOOP;
    }

    // ===== 连接相关方法 =====

    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
        totalConnections.increment();
    }

    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public void incrementConnectionErrors(String cause) {
        String key = cause != null && connectionErrorsByCause.containsKey(cause) ? cause : "other";
        connectionErrorsByCause.get(key).increment();
    }

    public void incrementConnectionErrors() {
        incrementConnectionErrors("other");
    }

    public int getActiveConnections() {
        return activeConnections.get();
    }

    // ===== 请求相关方法 =====

    public void incrementRequests() {
        totalRequests.increment();
    }

    public void recordRequestDuration(long durationMs) {
        requestDuration.record(Duration.ofMillis(durationMs));
    }

    public Timer.Sample startRequestTimer() {
        return Timer.start();
    }

    public void stopRequestTimer(Timer.Sample sample) {
        sample.stop(requestDuration);
    }

    public void incrementRequestErrors() {
        requestErrors.increment();
    }

    // ===== SSL 相关方法 =====

    public void incrementSslHandshakes() {
        sslHandshakes.increment();
    }

    public void incrementSslHandshakeFailures(String reason) {
        String key = reason != null && sslHandshakeFailuresByReason.containsKey(reason) ? reason : "other";
        sslHandshakeFailuresByReason.get(key).increment();
    }

    public void incrementSslHandshakeFailures() {
        incrementSslHandshakeFailures("other");
    }

    public void recordSslHandshakeDuration(long durationMs) {
        sslHandshakeDuration.record(Duration.ofMillis(durationMs));
    }

    // ===== 限流相关方法 =====

    public void incrementRateLimited() {
        rateLimitedRequests.increment();
    }

    public void incrementGlobalRateLimited() {
        globalRateLimitedRequests.increment();
        rateLimitedRequests.increment();
    }

    public void incrementClientRateLimited() {
        clientRateLimitedRequests.increment();
        rateLimitedRequests.increment();
    }

    // ===== 连接池相关方法 =====

    public void setPoolActiveConnections(int count) {
        poolActiveConnections.set(count);
    }

    public void setPoolIdleConnections(int count) {
        poolIdleConnections.set(count);
    }

    public void incrementPoolAcquireTimeouts() {
        poolAcquireTimeouts.increment();
    }

    // ===== 熔断器相关方法 =====

    /**
     * 设置熔断器状态
     * @param state 0=CLOSED, 1=OPEN, 2=HALF_OPEN
     */
    public void setCircuitBreakerState(int state) {
        int oldState = circuitBreakerState.getAndSet(state);
        if (oldState != state) {
            circuitBreakerStateTransitions.increment();
        }
    }

    public void incrementCircuitBreakerSuccessful() {
        circuitBreakerSuccessful.increment();
    }

    public void incrementCircuitBreakerFailed() {
        circuitBreakerFailed.increment();
    }

    public void incrementCircuitBreakerRejected() {
        circuitBreakerRejected.increment();
    }

    public int getCircuitBreakerState() {
        return circuitBreakerState.get();
    }

    // ===== 字节传输相关方法 =====

    public void addBytesReceived(long bytes) {
        bytesReceived.addAndGet(bytes);
    }

    public void addBytesSent(long bytes) {
        bytesSent.addAndGet(bytes);
    }

    public long getBytesReceived() {
        return bytesReceived.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    // ===== 证书过期相关方法 =====

    /**
     * 设置服务端证书过期剩余秒数
     *
     * @param seconds 距离过期的秒数
     */
    public void setServerCertExpirySeconds(long seconds) {
        serverCertExpirySeconds.set(seconds);
    }

    /**
     * 设置 CA 证书过期剩余秒数
     *
     * @param seconds 距离过期的秒数
     */
    public void setCaCertExpirySeconds(long seconds) {
        caCertExpirySeconds.set(seconds);
    }

    /**
     * 获取服务端证书过期剩余秒数
     *
     * @return 距离过期的秒数
     */
    public long getServerCertExpirySeconds() {
        return serverCertExpirySeconds.get();
    }

    /**
     * 获取 CA 证书过期剩余秒数
     *
     * @return 距离过期的秒数
     */
    public long getCaCertExpirySeconds() {
        return caCertExpirySeconds.get();
    }

    // ===== 其他方法 =====

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMillis) / 1000;
    }
}
