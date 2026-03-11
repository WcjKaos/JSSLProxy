package com.sslproxy.metrics;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 连接追踪器
 * <p>
 * 追踪所有活跃的客户端连接，用于：
 * - 优雅关闭时等待连接完成
 * - 统计当前连接数
 * - 按客户端证书指纹追踪连接
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ConnectionTracker {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionTracker.class);

    /** 所有活跃连接 */
    private final Set<Channel> activeChannels;

    /** 按客户端证书指纹分组的连接 */
    private final Map<String, Set<Channel>> channelsByFingerprint;

    /** 指标 */
    private final ProxyMetrics metrics;

    /**
     * 构造函数
     *
     * @param metrics 指标收集器
     */
    public ConnectionTracker(ProxyMetrics metrics) {
        this.metrics = metrics;
        this.activeChannels = ConcurrentHashMap.newKeySet();
        this.channelsByFingerprint = new ConcurrentHashMap<>();
        logger.info("ConnectionTracker initialized");
    }

    /**
     * 注册新连接
     *
     * @param channel 客户端通道
     */
    public void register(Channel channel) {
        activeChannels.add(channel);

        // 更新指标
        metrics.incrementActiveConnections();

        logger.debug("Connection registered: {}, total: {}", channel.remoteAddress(), activeChannels.size());
    }

    /**
     * 注册新连接并关联证书指纹
     *
     * @param channel     客户端通道
     * @param fingerprint 客户端证书指纹
     */
    public void register(Channel channel, String fingerprint) {
        register(channel);

        if (fingerprint != null && !fingerprint.isEmpty()) {
            channelsByFingerprint
                    .computeIfAbsent(fingerprint, k -> ConcurrentHashMap.newKeySet())
                    .add(channel);
            logger.debug("Connection associated with fingerprint: {}", fingerprint);
        }
    }

    /**
     * 注销连接
     *
     * @param channel 客户端通道
     */
    public void unregister(Channel channel) {
        boolean removed = activeChannels.remove(channel);

        if (removed) {
            // 从指纹映射中移除
            channelsByFingerprint.values().forEach(set -> set.remove(channel));

            // 更新指标
            metrics.decrementActiveConnections();

            logger.debug("Connection unregistered: {}, remaining: {}", channel.remoteAddress(), activeChannels.size());
        }
    }

    /**
     * 获取活跃连接数
     *
     * @return 活跃连接数
     */
    public int getActiveCount() {
        return activeChannels.size();
    }

    /**
     * 获取指定证书指纹的连接数
     *
     * @param fingerprint 证书指纹
     * @return 连接数
     */
    public int getCountByFingerprint(String fingerprint) {
        Set<Channel> channels = channelsByFingerprint.get(fingerprint);
        return channels != null ? channels.size() : 0;
    }

    /**
     * 获取所有活跃连接
     *
     * @return 活跃连接集合（不可变视图）
     */
    public Set<Channel> getActiveChannels() {
        return Set.copyOf(activeChannels);
    }

    /**
     * 检查是否有活跃连接
     *
     * @return true 如果有活跃连接
     */
    public boolean hasActiveConnections() {
        return !activeChannels.isEmpty();
    }

    /**
     * 终止指定证书指纹的所有活跃连接
     *
     * @param fingerprint 证书指纹（SHA-256）
     * @return 被终止的连接数
     */
    public int terminateByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return 0;
        }
        String normalized = fingerprint.trim().toUpperCase();
        Set<Channel> channels = channelsByFingerprint.get(normalized);
        if (channels == null || channels.isEmpty()) {
            return 0;
        }

        // Copy to avoid concurrent modification during close callbacks
        List<Channel> toClose = new ArrayList<>(channels);
        int count = 0;
        for (Channel ch : toClose) {
            if (ch.isActive()) {
                ch.close();
                count++;
            }
        }

        logger.info("Terminated {} connections for fingerprint: {}", count, normalized);
        return count;
    }

    /**
     * 关闭所有连接
     * 用于强制关闭场景
     */
    public void closeAll() {
        logger.info("Closing all {} active connections", activeChannels.size());

        for (Channel channel : activeChannels) {
            try {
                channel.close();
            } catch (Exception e) {
                logger.warn("Error closing channel: {}", e.getMessage());
            }
        }

        activeChannels.clear();
        channelsByFingerprint.clear();
    }

    /**
     * 等待所有连接关闭
     * <p>
     * 使用非阻塞方式等待，避免在 EventLoop 线程中调用时阻塞。
     * </p>
     *
     * @param timeoutSeconds 超时时间（秒）
     * @return true 如果所有连接都已关闭
     */
    public boolean awaitAllClosed(int timeoutSeconds) {
        if (!hasActiveConnections()) {
            return true;
        }

        // 使用 ScheduledExecutorService 进行非阻塞等待
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "connection-await-scheduler");
            t.setDaemon(true);
            return t;
        });

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        long deadlineMs = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        // 定期检查连接是否都已关闭
        scheduler.scheduleAtFixedRate(() -> {
            if (!hasActiveConnections()) {
                future.complete(true);
                scheduler.shutdown();
            } else if (System.currentTimeMillis() >= deadlineMs) {
                future.complete(false);
                scheduler.shutdown();
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        try {
            boolean allClosed = future.get(timeoutSeconds + 1, TimeUnit.SECONDS);
            if (!allClosed) {
                logger.warn("Timeout waiting for connections to close, {} remaining", getActiveCount());
            }
            return allClosed;
        } catch (Exception e) {
            logger.warn("Error waiting for connections to close: {}", e.getMessage());
            return false;
        } finally {
            scheduler.shutdownNow();
        }
    }
}
