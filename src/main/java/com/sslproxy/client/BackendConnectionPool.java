package com.sslproxy.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.SSLContextFactory;
import com.sslproxy.handler.ProxyBackendHandler;
import com.sslproxy.loadbalance.LoadBalancer.Backend;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.util.TlsUtil;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolHandler;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolMap;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;

/**
 * 后端连接池管理器
 * <p>
 * 管理到后端服务器的连接池，复用 TCP 连接以提高性能。
 * 支持连接健康检查、空闲超时、最大连接数限制等功能。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class BackendConnectionPool {
    private static final Logger logger = LoggerFactory.getLogger(BackendConnectionPool.class);
    private static final AttributeKey<String> POOL_KEY = AttributeKey.valueOf("backendPoolKey");

    private final ProxyConfig config;
    private final EventLoopGroup workerGroup;
    private final ProxyMetrics metrics;
    private final ChannelPoolMap<String, ChannelPool> poolMap;
    private final SslContext clientSslContext;

    // 连接池指标计数器
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger idleConnections = new AtomicInteger(0);

    /**
     * 构造后端连接池
     *
     * @param config      代理配置
     * @param workerGroup 共享的 Worker 线程组
     * @param metrics     代理指标
     */
    public BackendConnectionPool(ProxyConfig config, EventLoopGroup workerGroup, ProxyMetrics metrics) {
        this.config = config;
        this.workerGroup = workerGroup;
        this.metrics = metrics;

        // 如果目标是 HTTPS，初始化客户端 SSL 上下文
        SslContext sslCtx = null;
        if (needsClientSslContext(config)) {
            try {
                sslCtx = SSLContextFactory.createClientSslContext(config.ssl);
                logger.info("Client SSL context initialized for connection pool");
            } catch (Exception e) {
                logger.error("Failed to initialize client SSL context: {}", e.getMessage(), e);
            }
        }
        this.clientSslContext = sslCtx;

        // 创建连接池映射（支持多后端扩展）
        this.poolMap = new AbstractChannelPoolMap<>() {
            @Override
            protected ChannelPool newPool(String key) {
                return createPool(key);
            }
        };

        logger.info("Backend connection pool initialized: maxConnections={}, acquireTimeout={}ms",
                getMaxConnections(), getAcquireTimeout());
    }

    /**
     * 为指定后端创建连接池
     */
    private ChannelPool createPool(String key) {
        int timeout = (config.connection != null && config.connection.timeout > 0)
                ? config.connection.timeout : 30000;
        boolean keepalive = config.connection == null || config.connection.keepalive;
        int maxIdleTime = getMaxIdleTime();
        BackendTarget target = parseBackendKey(key);

        Bootstrap bootstrap = new Bootstrap()
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout)
                .option(ChannelOption.SO_KEEPALIVE, keepalive)
                .option(ChannelOption.TCP_NODELAY, true)
                .remoteAddress(target.host, target.port);

        return new FixedChannelPool(
                bootstrap,
                new BackendChannelPoolHandler(maxIdleTime, target, key),
                ChannelHealthChecker.ACTIVE,
                FixedChannelPool.AcquireTimeoutAction.FAIL,
                getAcquireTimeout(),
                getMaxConnections(),
                Integer.MAX_VALUE,  // maxPendingAcquires
                true,               // releaseHealthCheck
                true                // lastRecentUsed
        );
    }

    /**
     * 获取后端连接
     *
     * @param inboundChannel 入站通道（用于设置 ProxyBackendHandler）
     * @return 连接获取 Future
     */
    public Future<Channel> acquire(Channel inboundChannel) {
        String poolKey = buildBackendKey(config.target.host, config.target.port, config.target.protocol);
        ChannelPool pool = poolMap.get(poolKey);

        Future<Channel> future = pool.acquire();
        future.addListener(f -> {
            if (f.isSuccess()) {
                Channel channel = (Channel) f.getNow();
                try {
                    // 更新指标（使用原子操作确保不为负）
                    activeConnections.incrementAndGet();
                    idleConnections.updateAndGet(val -> Math.max(0, val - 1));
                    updateMetrics();

                    // 动态添加后端处理器
                    ChannelPipeline pipeline = channel.pipeline();

                    // 安全移除旧的后端处理器（使用 try-catch 防止竞态条件）
                    try {
                        if (pipeline.get("proxy-backend") != null) {
                            pipeline.remove("proxy-backend");
                        }
                    } catch (Exception e) {
                        // Handler 可能已被其他线程移除，忽略
                        logger.debug("Handler already removed: {}", e.getMessage());
                    }
                    // 添加新的后端处理器
                    pipeline.addLast("proxy-backend", new ProxyBackendHandler(inboundChannel));

                    logger.debug("Acquired connection from pool: {}", channel.remoteAddress());
                } catch (Exception e) {
                    logger.error("Failed to configure acquired connection, releasing to pool", e);
                    // 配置失败，回滚计数器并释放回连接池（池会关闭不健康的连接）
                    activeConnections.updateAndGet(val -> Math.max(0, val - 1));
                    updateMetrics();
                    pool.release(channel);
                }
            } else {
                logger.error("Failed to acquire connection from pool: {}", f.cause().getMessage());
                // 记录获取超时
                metrics.incrementPoolAcquireTimeouts();
            }
        });

        return future;
    }

    public Future<Channel> acquire(Channel inboundChannel, String host, int port, String protocol, Backend backend) {
        String poolKey = buildBackendKey(host, port, protocol);
        ChannelPool pool = poolMap.get(poolKey);

        Future<Channel> future = pool.acquire();
        future.addListener(f -> {
            if (f.isSuccess()) {
                Channel channel = (Channel) f.getNow();
                try {
                    // 更新指标（使用原子操作确保不为负）
                    activeConnections.incrementAndGet();
                    idleConnections.updateAndGet(val -> Math.max(0, val - 1));
                    updateMetrics();

                    if (backend != null) {
                        backend.incrementConnections();
                    }

                    // 动态添加后端处理器鐞嗗櫒
                    ChannelPipeline pipeline = channel.pipeline();

                    try {
                        if (pipeline.get("proxy-backend") != null) {
                            pipeline.remove("proxy-backend");
                        }
                    } catch (Exception e) {
                        logger.debug("Handler already removed: {}", e.getMessage());
                    }
                    pipeline.addLast("proxy-backend", new ProxyBackendHandler(inboundChannel));

                    logger.debug("Acquired connection from pool: {}", channel.remoteAddress());
                } catch (Exception e) {
                    logger.error("Failed to configure acquired connection, releasing to pool", e);
                    activeConnections.updateAndGet(val -> Math.max(0, val - 1));
                    updateMetrics();
                    pool.release(channel);
                    if (backend != null) {
                        backend.decrementConnections();
                    }
                }
            } else {
                logger.error("Failed to acquire connection from pool: {}", f.cause().getMessage());
                metrics.incrementPoolAcquireTimeouts();
            }
        });

        return future;
    }

    /**
     * 释放连接回连接池
     *
     * @param channel 要释放的连接
     */
    public void release(Channel channel) {
        release(channel, null);
    }

    public void release(Channel channel, Backend backend) {
        if (channel == null) {
            return;
        }

        // 更新指标（使用原子操作确保不为负）
        activeConnections.updateAndGet(val -> Math.max(0, val - 1));

        if (backend != null) {
            backend.decrementConnections();
        }

        String poolKey = channel.attr(POOL_KEY).get();
        if (poolKey == null || poolKey.isEmpty()) {
            poolKey = buildBackendKey(config.target.host, config.target.port, config.target.protocol);
        }
        ChannelPool pool = poolMap.get(poolKey);

        // 安全移除后端处理器，避免下次复用时状态混乱
        ChannelPipeline pipeline = channel.pipeline();
        try {
            if (pipeline.get("proxy-backend") != null) {
                pipeline.remove("proxy-backend");
            }
        } catch (Exception e) {
            // Handler 可能已被移除，忽略
            logger.debug("Handler already removed during release: {}", e.getMessage());
        }

        // 如果连接已不活跃，仍需通过 pool.release 归还槽位，否则池容量永久泄漏
        if (!channel.isActive()) {
            logger.debug("Channel is not active, releasing to pool for cleanup: {}", channel.remoteAddress());
            updateMetrics();
            pool.release(channel);
            return;
        }

        idleConnections.incrementAndGet();
        updateMetrics();
        pool.release(channel);
        logger.debug("Released connection back to pool: {}", channel.remoteAddress());
    }

    /**
     * 更新 ProxyMetrics 中的连接池指标
     */
    private void updateMetrics() {
        metrics.setPoolActiveConnections(Math.max(0, activeConnections.get()));
        metrics.setPoolIdleConnections(Math.max(0, idleConnections.get()));
    }

    /**
     * 获取活跃连接数
     *
     * @return 活跃连接数
     */
    public int getActiveConnections() {
        return Math.max(0, activeConnections.get());
    }

    /**
     * 获取空闲连接数
     *
     * @return 空闲连接数
     */
    public int getIdleConnections() {
        return Math.max(0, idleConnections.get());
    }

    /**
     * 关闭连接池
     */
    public void close() {
        if (poolMap instanceof AbstractChannelPoolMap) {
            ((AbstractChannelPoolMap<String, ChannelPool>) poolMap).close();
        }
        logger.info("Backend connection pool closed");
    }

    private int getMaxConnections() {
        if (config.performance != null && config.performance.backendPool != null) {
            return config.performance.backendPool.maxConnections;
        }
        return 100;
    }

    private int getAcquireTimeout() {
        if (config.performance != null && config.performance.backendPool != null) {
            return config.performance.backendPool.acquireTimeout;
        }
        return 5000;
    }

    private int getMaxIdleTime() {
        if (config.performance != null && config.performance.backendPool != null) {
            return config.performance.backendPool.maxIdleTime;
        }
        return 60000;
    }

    private boolean needsClientSslContext(ProxyConfig config) {
        if (config == null || config.target == null) {
            return false;
        }
        if ("https".equalsIgnoreCase(config.target.protocol)) {
            return true;
        }
        if (config.target.backends != null) {
            for (ProxyConfig.BackendConfig backend : config.target.backends) {
                if (backend != null && "https".equalsIgnoreCase(backend.protocol)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String buildBackendKey(String host, int port, String protocol) {
        String proto = protocol != null ? protocol.toLowerCase() : "http";
        return proto + "://" + host + ":" + port;
    }

    private BackendTarget parseBackendKey(String key) {
        String host = config.target.host;
        int port = config.target.port;
        String protocol = config.target.protocol != null ? config.target.protocol : "http";

        if (key == null || key.isEmpty()) {
            return new BackendTarget(host, port, protocol);
        }

        String[] parts = key.split("://", 2);
        String remainder = key;
        if (parts.length == 2) {
            protocol = parts[0];
            remainder = parts[1];
        }
        int colonIndex = remainder.lastIndexOf(':');
        if (colonIndex > 0 && colonIndex < remainder.length() - 1) {
            host = remainder.substring(0, colonIndex);
            try {
                port = Integer.parseInt(remainder.substring(colonIndex + 1));
            } catch (NumberFormatException ignored) {
                port = config.target.port;
            }
        } else {
            host = remainder;
        }
        return new BackendTarget(host, port, protocol);
    }

    private static final class BackendTarget {
        final String host;
        final int port;
        final String protocol;

        BackendTarget(String host, int port, String protocol) {
            this.host = host;
            this.port = port;
            this.protocol = protocol != null ? protocol : "http";
        }
    }

    /**
     * 连接池处理器
     * 负责初始化新建的连接
     */
    private class BackendChannelPoolHandler extends AbstractChannelPoolHandler {
        private final int maxIdleTimeSeconds;
        private final BackendTarget target;
        private final String poolKey;

        public BackendChannelPoolHandler(int maxIdleTimeMs, BackendTarget target, String poolKey) {
            this.maxIdleTimeSeconds = maxIdleTimeMs / 1000;
            this.target = target;
            this.poolKey = poolKey;
        }

        @Override
        public void channelCreated(Channel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            ch.attr(POOL_KEY).set(poolKey);

            // 新连接创建时，增加空闲计数
            idleConnections.incrementAndGet();
            updateMetrics();

            // 空闲检测（超时后关闭连接）
            pipeline.addLast("idle-handler",
                    new IdleStateHandler(0, 0, maxIdleTimeSeconds, TimeUnit.SECONDS));

            // 如果目标是 HTTPS，添加 SSL Handler
            if ("https".equalsIgnoreCase(target.protocol) && clientSslContext != null) {
                SslHandler sslHandler = clientSslContext.newHandler(
                        ch.alloc(), target.host, target.port);
                TlsUtil.enableHostnameVerification(sslHandler.engine());
                pipeline.addLast("ssl", sslHandler);
                logger.debug("SSL handler added for pooled connection");
            }

            // HTTP 编解码器
            pipeline.addLast("http-codec", new HttpClientCodec());

            // HTTP 消息聚合器（使用配置的最大请求大小）
            int maxRequestSize = config.performance != null && config.performance.maxRequestSize > 0
                    ? config.performance.maxRequestSize
                    : 10 * 1024 * 1024;  // 默认 10MB
            pipeline.addLast("http-aggregator", new HttpObjectAggregator(maxRequestSize));

            logger.debug("New connection created in pool: {}", ch.remoteAddress());
        }

        @Override
        public void channelReleased(Channel ch) throws Exception {
            logger.debug("Connection released to pool: {}", ch.remoteAddress());
        }

        @Override
        public void channelAcquired(Channel ch) throws Exception {
            logger.debug("Connection acquired from pool: {}", ch.remoteAddress());
        }
    }
}
