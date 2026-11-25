package com.sslproxy.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.SslContextHolder;
import com.sslproxy.handler.AccessLogWriter;
import com.sslproxy.loadbalance.LoadBalancerManager;
import com.sslproxy.metrics.ConnectionTracker;
import com.sslproxy.metrics.MetricsRegistry;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.ratelimit.RateLimiterManager;
import com.sslproxy.resilience.BackendCircuitBreaker;
import com.sslproxy.security.CertificateRevocationChecker;
import com.sslproxy.security.DynamicCertificateManager;
import com.sslproxy.security.RejectedCertTracker;

import io.netty.channel.EventLoopGroup;

/**
 * 应用上下文 — 持有所有服务实例，替代全局单例。
 * <p>
 * 通过 {@link #create(ProxyConfig, EventLoopGroup)} 工厂方法按依赖顺序构建。
 * 只在 {@link ProxyServer} 中创建，通过构造器向下传递给 Handler。
 * </p>
 */
public final class ProxyContext {
    private static final Logger logger = LoggerFactory.getLogger(ProxyContext.class);

    private final AuditLogger auditLogger;
    private final MetricsRegistry metricsRegistry;
    private final ProxyMetrics metrics;
    private final ConnectionTracker connectionTracker;
    private final RejectedCertTracker rejectedCertTracker;
    private final CertificateRevocationChecker revocationChecker;
    private final RateLimiterManager rateLimiterManager;
    private final BackendCircuitBreaker circuitBreaker;
    private final LoadBalancerManager loadBalancerManager;
    private final SslContextHolder sslContextHolder;
    private final DynamicCertificateManager dynamicCertManager;
    private final AccessLogWriter accessLogWriter;

    private ProxyContext(AuditLogger auditLogger,
                         MetricsRegistry metricsRegistry,
                         ProxyMetrics metrics,
                         ConnectionTracker connectionTracker,
                         RejectedCertTracker rejectedCertTracker,
                         CertificateRevocationChecker revocationChecker,
                         RateLimiterManager rateLimiterManager,
                         BackendCircuitBreaker circuitBreaker,
                         LoadBalancerManager loadBalancerManager,
                         SslContextHolder sslContextHolder,
                         DynamicCertificateManager dynamicCertManager,
                         AccessLogWriter accessLogWriter) {
        this.auditLogger = auditLogger;
        this.metricsRegistry = metricsRegistry;
        this.metrics = metrics;
        this.connectionTracker = connectionTracker;
        this.rejectedCertTracker = rejectedCertTracker;
        this.revocationChecker = revocationChecker;
        this.rateLimiterManager = rateLimiterManager;
        this.circuitBreaker = circuitBreaker;
        this.loadBalancerManager = loadBalancerManager;
        this.sslContextHolder = sslContextHolder;
        this.dynamicCertManager = dynamicCertManager;
        this.accessLogWriter = accessLogWriter;
    }

    /**
     * 按依赖顺序构建全部服务实例。
     *
     * @param config      代理配置
     * @param workerGroup Netty worker 线程组（供 LoadBalancerManager 使用）
     * @return 完整构建的 ProxyContext
     * @throws Exception 如果 SSL 上下文创建失败
     */
    public static ProxyContext create(ProxyConfig config, EventLoopGroup workerGroup) throws Exception {
        // ── 1. 基础设施层（无依赖）──
        AuditLogger auditLogger = new AuditLogger();

        MetricsRegistry metricsRegistry = new MetricsRegistry(config);
        ProxyMetrics metrics;
        if (metricsRegistry.isEnabled()) {
            metrics = new ProxyMetrics(metricsRegistry.getRegistry(), config.metrics.histogramBuckets);
            logger.info("✓ Metrics system initialized");
        } else {
            metrics = ProxyMetrics.noop();
            logger.info("Metrics system disabled");
        }

        RejectedCertTracker rejectedCertTracker = new RejectedCertTracker(100);

        // ── 2. 连接跟踪（依赖 ProxyMetrics）──
        ConnectionTracker connectionTracker = new ConnectionTracker(metrics);

        // ── 3. 业务服务层 ──

        // 证书吊销检查器
        CertificateRevocationChecker revocationChecker = null;
        if (config.ssl != null && config.ssl.revocation != null && config.ssl.revocation.enabled) {
            revocationChecker = new CertificateRevocationChecker(config.ssl.revocation);
            logger.info("✓ Certificate revocation checker initialized: OCSP={}, CRL={}, softFail={}",
                    config.ssl.revocation.ocspEnabled,
                    config.ssl.revocation.crlEnabled,
                    config.ssl.revocation.softFail);
        } else {
            logger.info("Certificate revocation checking disabled");
        }

        // 限流器
        RateLimiterManager rateLimiterManager = new RateLimiterManager(config.rateLimit);
        if (rateLimiterManager.isEnabled()) {
            logger.info("✓ Rate limiter initialized: globalRps={}, clientRps={}",
                    config.rateLimit.globalRps, config.rateLimit.clientRps);
        } else {
            logger.info("Rate limiter disabled");
        }

        // 熔断器
        BackendCircuitBreaker circuitBreaker = null;
        if (config.circuitBreaker != null && config.circuitBreaker.enabled) {
            circuitBreaker = new BackendCircuitBreaker(
                    config.circuitBreaker, metricsRegistry, metrics, auditLogger);
            logger.info("✓ Circuit breaker initialized: failureRateThreshold={}%, waitDuration={}s",
                    config.circuitBreaker.failureRateThreshold,
                    config.circuitBreaker.waitDurationInOpenState);
        } else {
            logger.info("Circuit breaker disabled");
        }

        // 负载均衡器
        LoadBalancerManager loadBalancerManager = null;
        if (config.target != null) {
            loadBalancerManager = new LoadBalancerManager(config.target, workerGroup, config.ssl);
            logger.info("✓ Load balancer initialized: strategy={}, backends={}",
                    config.target.strategy,
                    loadBalancerManager.getLoadBalancer().getBackends().size());
        }

        // 动态证书管理器
        DynamicCertificateManager dynamicCertManager = new DynamicCertificateManager();

        // SSL 上下文
        SslContextHolder sslContextHolder = new SslContextHolder(
                config.ssl, metrics, auditLogger, dynamicCertManager, revocationChecker);
        logger.info("✓ SSL context initialized (hot-reload enabled)");

        // 访问日志写入器
        AccessLogWriter accessLogWriter = null;
        if (config.accessLog != null && config.accessLog.enabled
                && config.accessLog.async && config.accessLog.path != null) {
            accessLogWriter = new AccessLogWriter(config.accessLog.path, config.accessLog.bufferSize);
            accessLogWriter.start();
            logger.info("✓ Access log async writer started: {}", config.accessLog.path);
        }

        return new ProxyContext(auditLogger, metricsRegistry, metrics, connectionTracker,
                rejectedCertTracker, revocationChecker, rateLimiterManager, circuitBreaker,
                loadBalancerManager, sslContextHolder, dynamicCertManager, accessLogWriter);
    }

    // ── Getters ──

    public AuditLogger auditLogger() { return auditLogger; }
    public MetricsRegistry metricsRegistry() { return metricsRegistry; }
    public ProxyMetrics metrics() { return metrics; }
    public ConnectionTracker connectionTracker() { return connectionTracker; }
    public RejectedCertTracker rejectedCertTracker() { return rejectedCertTracker; }
    /** @return null if revocation checking is disabled */
    public CertificateRevocationChecker revocationChecker() { return revocationChecker; }
    public RateLimiterManager rateLimiterManager() { return rateLimiterManager; }
    /** @return null if circuit breaker is disabled */
    public BackendCircuitBreaker circuitBreaker() { return circuitBreaker; }
    /** @return null if no target configured */
    public LoadBalancerManager loadBalancerManager() { return loadBalancerManager; }
    public SslContextHolder sslContextHolder() { return sslContextHolder; }
    public DynamicCertificateManager dynamicCertManager() { return dynamicCertManager; }
    /** @return null if async access log is disabled */
    public AccessLogWriter accessLogWriter() { return accessLogWriter; }

    /**
     * 关闭所有服务。
     */
    public void shutdown() {
        sslContextHolder.close();

        if (loadBalancerManager != null) {
            loadBalancerManager.stop();
        }

        if (accessLogWriter != null) {
            accessLogWriter.stop();
        }

        metricsRegistry.close();

        logger.info("ProxyContext shutdown complete");
    }
}
