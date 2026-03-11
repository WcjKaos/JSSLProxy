package com.sslproxy.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.client.BackendConnectionPool;
import com.sslproxy.config.ConfigWatcher;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.server.ProxyContext;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * 管理服务器
 * <p>
 * 独立端口的 HTTP 服务器，提供以下端点：
 * - /health - 健康检查
 * - /ready - 就绪检查
 * - /metrics - Prometheus 指标
 * - /admin/* - 管理 API
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ManagementServer {
    private static final Logger logger = LoggerFactory.getLogger(ManagementServer.class);

    private final ProxyConfig config;
    private final ProxyContext ctx;
    private final BackendConnectionPool connectionPool;
    private final ConfigWatcher configWatcher;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture serverChannelFuture;

    public ManagementServer(ProxyConfig config, ProxyContext ctx,
                            BackendConnectionPool connectionPool, ConfigWatcher configWatcher) {
        this.config = config;
        this.ctx = ctx;
        this.connectionPool = connectionPool;
        this.configWatcher = configWatcher;
    }

    /**
     * 启动管理服务器
     *
     * @throws Exception 如果启动失败
     */
    public void start() throws Exception {
        if (config.management == null || !config.management.enabled) {
            logger.info("Management server is disabled");
            return;
        }

        logger.info("Starting Management Server...");

        // 使用独立的线程组
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ManagementInitializer(config, ctx, connectionPool, configWatcher));

            String host = config.management.host != null ? config.management.host : "0.0.0.0";
            int port = config.management.port > 0 ? config.management.port : 9090;

            serverChannelFuture = bootstrap.bind(host, port).sync();

            logger.info("Management Server started on {}:{}", host, port);
            logger.info("  /health  - Health check endpoint");
            logger.info("  /ready   - Readiness check endpoint");
            logger.info("  /metrics - Prometheus metrics endpoint");
            logger.info("  /admin/* - Admin API endpoints");

        } catch (Exception e) {
            shutdown();
            throw e;
        }
    }

    /**
     * 关闭管理服务器
     */
    public void shutdown() {
        logger.info("Shutting down Management Server...");

        if (serverChannelFuture != null) {
            serverChannelFuture.channel().close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        logger.info("Management Server stopped");
    }

    /**
     * 检查服务器是否正在运行
     *
     * @return true 如果正在运行
     */
    public boolean isRunning() {
        return serverChannelFuture != null && serverChannelFuture.channel().isActive();
    }
}
