package com.sslproxy.server;

import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.client.BackendConnectionPool;
import com.sslproxy.config.ConfigWatcher;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.management.ManagementServer;
import com.sslproxy.server.TransportFactory.TransportType;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

/**
 * SSL 代理服务器
 * <p>
 * 基于 Netty 实现的高性能 SSL/TLS 代理服务器。
 * 支持客户端证书认证（mTLS）和安全的流量转发。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ProxyServer {
    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);

    private final ProxyConfig config;
    private final String configFilePath;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture serverChannelFuture;
    private BackendConnectionPool connectionPool;
    private ManagementServer managementServer;
    private GracefulShutdownManager shutdownManager;
    private ConfigWatcher configWatcher;
    private ProxyContext ctx;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private volatile Thread shutdownHook;

    /**
     * 构造代理服务器
     *
     * @param config 代理配置对象
     */
    public ProxyServer(ProxyConfig config) {
        this(config, null);
    }

    /**
     * 构造代理服务器（支持配置热加载）
     *
     * @param config         代理配置对象
     * @param configFilePath 配置文件路径（用于热加载，可为 null）
     */
    public ProxyServer(ProxyConfig config, String configFilePath) {
        this.config = config;
        this.configFilePath = configFilePath;
    }

    /**
     * 启动代理服务器
     *
     * @throws Exception 如果启动失败
     */
    public void start() throws Exception {
        logger.info("Starting SSL Proxy Server...");
        logger.info("Configuration: {}:{} -> {}:{} ({})",
                config.server.host, config.server.port,
                config.target.host, config.target.port, config.target.protocol);

        // 初始化传输层工厂（自动检测最佳传输方式）
        TransportFactory.initialize();
        TransportType transportType = TransportFactory.getTransportType();

        // 创建线程组
        bossGroup = TransportFactory.createEventLoopGroup(1);

        int workerThreads = (config.performance != null && config.performance.workerThreads > 0)
                ? config.performance.workerThreads : 0;
        workerGroup = TransportFactory.createEventLoopGroup(workerThreads);

        if (workerThreads > 0) {
            logger.info("Worker threads configured: {}", workerThreads);
        } else {
            logger.info("Worker threads: default (CPU cores * 2)");
        }
        logger.info("Transport type: {}", transportType.getDescription());

        // 构建应用上下文（所有服务实例在此创建）
        ctx = ProxyContext.create(config, workerGroup);

        // 创建优雅关闭管理器
        shutdownManager = new GracefulShutdownManager(config, ctx.connectionTracker(), ctx.metricsRegistry());

        // 从配置读取 backlog 值
        int backlog = (config.performance != null && config.performance.backlog > 0)
                ? config.performance.backlog : 1024;

        // 创建后端连接池（如果启用）
        boolean poolEnabled = config.performance != null
                && config.performance.backendPool != null
                && config.performance.backendPool.enabled;
        if (poolEnabled) {
            connectionPool = new BackendConnectionPool(config, workerGroup, ctx.metrics());
            logger.info("Backend connection pool enabled: maxConnections={}",
                    config.performance.backendPool.maxConnections);
        } else {
            connectionPool = null;
            logger.info("Backend connection pool disabled");
        }

        // 启动负载均衡器健康检查
        if (ctx.loadBalancerManager() != null
                && config.target.healthCheck != null && config.target.healthCheck.enabled) {
            ctx.loadBalancerManager().startHealthCheck();
        }

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(TransportFactory.getServerChannelClass())
                    .option(ChannelOption.SO_BACKLOG, backlog)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ServerInitializer(config, ctx, connectionPool));

            logger.info("SO_BACKLOG configured: {}", backlog);

            // 绑定端口并启动服务器
            String bindHost = config.server.host != null ? config.server.host : "0.0.0.0";
            serverChannelFuture = bootstrap.bind(bindHost, config.server.port).sync();

            logger.info("✓ SSL Proxy Server started successfully");
            logger.info("✓ Listening on {}:{}", bindHost, config.server.port);
            logger.info("✓ Client Auth Mode: {}", config.ssl.clientAuth);
            logger.info("✓ TLS Protocols: {}", config.ssl.protocols);

            // 先启动配置热加载
            startConfigWatcher();

            // 启动管理服务器
            startManagementServer();

            // 启动 SSL 证书热加载监视
            startCertificateWatcher();

            // 初始化动态证书名单持久化
            initializeDynamicCertPersistence();

            // 注册优雅关闭钩子
            registerShutdownHook();

            // 等待服务器 socket 关闭
            serverChannelFuture.channel().closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    /**
     * 优雅关闭服务器
     */
    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            logger.debug("Shutdown already in progress, skipping duplicate call");
            return;
        }
        logger.info("Shutting down SSL Proxy Server...");

        // 移除 shutdown hook
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 正在关闭，无法移除 hook
            }
            shutdownHook = null;
        }

        // 关闭管理服务器
        if (managementServer != null) {
            managementServer.shutdown();
        }

        // 关闭配置监视器
        if (configWatcher != null) {
            configWatcher.close();
        }

        // 关闭后端连接池
        if (connectionPool != null) {
            connectionPool.close();
        }

        // 关闭应用上下文（SSL 证书监视器、负载均衡器、访问日志、指标等）
        if (ctx != null) {
            ctx.shutdown();
        }

        // 使用优雅关闭管理器
        if (shutdownManager != null) {
            shutdownManager.shutdown(bossGroup, workerGroup);
        } else {
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
        }

        logger.info("SSL Proxy Server stopped");
    }

    private void registerShutdownHook() {
        shutdownHook = new Thread(() -> {
            logger.info("Received shutdown signal");
            shutdown();
        }, "proxy-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        logger.debug("Shutdown hook registered");
    }

    public boolean isRunning() {
        return serverChannelFuture != null && serverChannelFuture.channel().isActive();
    }

    public ProxyConfig getConfig() {
        return config;
    }

    private void initializeDynamicCertPersistence() {
        String pathStr;
        if (config.security != null
                && config.security.dynamicListPersistencePath != null
                && !config.security.dynamicListPersistencePath.isEmpty()) {
            pathStr = config.security.dynamicListPersistencePath;
        } else if (configFilePath != null && !configFilePath.isEmpty()) {
            pathStr = Paths.get(configFilePath).toAbsolutePath().getParent()
                    .resolve("dynamic-lists.json").toString();
        } else {
            pathStr = "dynamic-lists.json";
        }
        ctx.dynamicCertManager().setPersistencePath(Paths.get(pathStr));
        logger.info("✓ Dynamic certificate list persistence: {}", pathStr);
    }

    private void startManagementServer() {
        if (config.management != null && config.management.enabled) {
            try {
                managementServer = new ManagementServer(config, ctx, connectionPool, configWatcher);
                managementServer.start();
            } catch (Exception e) {
                logger.error("Failed to start management server: {}", e.getMessage(), e);
            }
        }
    }

    private void startConfigWatcher() {
        if (configFilePath != null && !configFilePath.isEmpty()) {
            try {
                configWatcher = new ConfigWatcher(configFilePath, config);
                configWatcher.addListener(newConfig -> {
                    // 配置变更时重新加载限流器
                    if (newConfig.rateLimit != null) {
                        ctx.rateLimiterManager().reload(newConfig.rateLimit);
                        logger.info("Rate limiter reloaded from config change");
                    }
                });
                configWatcher.start();
                logger.info("✓ Config hot-reload enabled for: {}", configFilePath);
            } catch (Exception e) {
                logger.warn("Failed to start config watcher: {}", e.getMessage());
            }
        }
    }

    private void startCertificateWatcher() {
        ctx.sslContextHolder().startWatching();
        logger.info("✓ SSL certificate hot-reload enabled");
    }
}
