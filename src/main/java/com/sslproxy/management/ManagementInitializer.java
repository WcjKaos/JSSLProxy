package com.sslproxy.management;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.client.BackendConnectionPool;
import com.sslproxy.config.ConfigWatcher;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.management.handler.AdminApiHandler;
import com.sslproxy.management.handler.HealthHandler;
import com.sslproxy.management.handler.ManagementAuthHandler;
import com.sslproxy.management.handler.MetricsHandler;
import com.sslproxy.server.ProxyContext;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

/**
 * 管理服务器通道初始化器
 * <p>
 * 配置无 SSL 的 HTTP Pipeline，用于健康检查和指标暴露。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ManagementInitializer extends ChannelInitializer<SocketChannel> {
    private static final Logger logger = LoggerFactory.getLogger(ManagementInitializer.class);

    private final ProxyConfig config;
    private final ProxyContext ctx;
    private final BackendConnectionPool connectionPool;
    private final ConfigWatcher configWatcher;

    public ManagementInitializer(ProxyConfig config, ProxyContext ctx,
                                 BackendConnectionPool connectionPool, ConfigWatcher configWatcher) {
        this.config = config;
        this.ctx = ctx;
        this.connectionPool = connectionPool;
        this.configWatcher = configWatcher;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP 编解码器
        pipeline.addLast("http-codec", new HttpServerCodec());

        // HTTP 消息聚合器（1MB 足够管理端点使用）
        pipeline.addLast("http-aggregator", new HttpObjectAggregator(1024 * 1024));

        // 管理鉴权处理器（默认仅保护 /admin/*）
        pipeline.addLast("auth", new ManagementAuthHandler(config, ctx.auditLogger()));

        // 健康检查处理器
        pipeline.addLast("health", new HealthHandler(config, connectionPool,
                ctx.metrics(), ctx.loadBalancerManager(), ctx.sslContextHolder()));

        // 指标处理器
        pipeline.addLast("metrics", new MetricsHandler(ctx.metricsRegistry()));

        // 管理 API 处理器
        pipeline.addLast("admin", new AdminApiHandler(config, configWatcher,
                ctx.dynamicCertManager(), ctx.connectionTracker(), ctx.rejectedCertTracker(),
                ctx.sslContextHolder(), ctx.auditLogger(), ctx.metrics()));

        logger.debug("Management pipeline initialized for {}", ch.remoteAddress());
    }
}
