package com.sslproxy.server;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.metrics.ConnectionTracker;
import com.sslproxy.metrics.MetricsRegistry;

import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 优雅关闭管理器
 * <p>
 * 负责协调服务器的优雅关闭流程：
 * 1. 停止接受新连接
 * 2. 等待现有请求完成
 * 3. 关闭连接池
 * 4. 关闭线程组
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class GracefulShutdownManager {
    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownManager.class);

    private final ProxyConfig config;
    private final ConnectionTracker connectionTracker;
    private final MetricsRegistry metricsRegistry;
    private final int timeoutSeconds;
    private final boolean awaitRequests;

    /**
     * 构造优雅关闭管理器
     *
     * @param config            代理配置
     * @param connectionTracker 连接追踪器
     * @param metricsRegistry   指标注册中心
     */
    public GracefulShutdownManager(ProxyConfig config, ConnectionTracker connectionTracker, MetricsRegistry metricsRegistry) {
        this.config = config;
        this.connectionTracker = connectionTracker;
        this.metricsRegistry = metricsRegistry;
        this.timeoutSeconds = config.shutdown != null ? config.shutdown.timeoutSeconds : 60;
        this.awaitRequests = config.shutdown == null || config.shutdown.awaitRequests;
    }

    /**
     * 执行优雅关闭
     *
     * @param bossGroup   Boss 线程组
     * @param workerGroup Worker 线程组
     */
    public void shutdown(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        logger.info("Initiating graceful shutdown (timeout={}s, awaitRequests={})",
                timeoutSeconds, awaitRequests);

        long startTime = System.currentTimeMillis();

        // 1. 停止接受新连接（通过关闭 boss 线程组）
        logger.info("Step 1: Stopping acceptance of new connections...");
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }

        // 2. 等待现有请求完成
        if (awaitRequests) {
            logger.info("Step 2: Waiting for existing connections to complete...");
            ConnectionTracker tracker = connectionTracker;

            if (tracker != null && tracker.hasActiveConnections()) {
                int activeConnections = tracker.getActiveCount();
                logger.info("Active connections: {}", activeConnections);

                // 等待连接关闭
                boolean allClosed = tracker.awaitAllClosed(timeoutSeconds);

                if (allClosed) {
                    logger.info("All connections closed gracefully");
                } else {
                    int remaining = tracker.getActiveCount();
                    logger.warn("Timeout waiting for connections, {} remaining. Force closing...", remaining);
                    tracker.closeAll();
                }
            } else {
                logger.info("No active connections to wait for");
            }
        }

        // 3. 关闭 worker 线程组
        logger.info("Step 3: Shutting down worker threads...");
        if (workerGroup != null) {
            Future<?> workerFuture = workerGroup.shutdownGracefully(0, 15, TimeUnit.SECONDS);
            try {
                workerFuture.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for worker shutdown");
            }
        }

        // 4. 关闭指标注册中心
        logger.info("Step 4: Closing metrics registry...");
        if (metricsRegistry != null) {
            metricsRegistry.close();
        }

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Graceful shutdown completed in {}ms", duration);
    }

    /**
     * 获取超时时间
     *
     * @return 超时时间（秒）
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 是否等待请求完成
     *
     * @return true 如果等待
     */
    public boolean isAwaitRequests() {
        return awaitRequests;
    }
}
