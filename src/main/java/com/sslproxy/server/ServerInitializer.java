package com.sslproxy.server;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.client.BackendConnectionPool;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.SslContextHolder;
import com.sslproxy.handler.AccessLogHandler;
import com.sslproxy.handler.MaxConnectionsHandler;
import com.sslproxy.handler.MetricsCollectorHandler;
import com.sslproxy.handler.ProxyFrontendHandler;
import com.sslproxy.handler.RequestIdHandler;
import com.sslproxy.handler.SSLAuthHandler;
import com.sslproxy.ratelimit.RateLimitHandler;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * 服务器通道初始化器
 * <p>
 * 配置 Netty 的 ChannelPipeline，添加 SSL Handler、日志 Handler 和代理逻辑 Handler。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ServerInitializer extends ChannelInitializer<SocketChannel> {
    private static final Logger logger = LoggerFactory.getLogger(ServerInitializer.class);

    private static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 60;
    private static final long DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final int DEFAULT_MAX_REQUEST_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final int MILLIS_PER_SECOND = 1000;

    private final ProxyConfig config;
    private final ProxyContext ctx;
    private final BackendConnectionPool connectionPool;
    private final MaxConnectionsHandler maxConnectionsHandler;

    /**
     * 构造服务器初始化器
     *
     * @param config         代理配置
     * @param ctx            应用上下文
     * @param connectionPool 后端连接池（可以为 null）
     */
    public ServerInitializer(ProxyConfig config, ProxyContext ctx, BackendConnectionPool connectionPool) {
        this.config = config;
        this.ctx = ctx;
        this.connectionPool = connectionPool;

        // 初始化最大连接数限制 Handler（Sharable，可在所有 Channel 间共享）
        int maxClientConnections = config.performance != null ? config.performance.maxClientConnections : 10000;
        this.maxConnectionsHandler = new MaxConnectionsHandler(maxClientConnections);

        logger.info("Server SSL context initialized successfully (hot-reload enabled)");
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // 0. 最大连接数限制（必须在最前面，尽早拒绝超限连接）
        pipeline.addLast("max-connections", maxConnectionsHandler);

        // 1. 空闲连接检测（读超时、写超时、全超时）
        int timeoutSeconds = config.connection != null && config.connection.timeout > 0
                ? config.connection.timeout / MILLIS_PER_SECOND
                : DEFAULT_IDLE_TIMEOUT_SECONDS;
        pipeline.addLast("idle-state", new IdleStateHandler(timeoutSeconds, timeoutSeconds, timeoutSeconds * 2, TimeUnit.SECONDS));

        // 2. SSL Handler - 处理 TLS 握手和加密
        SslContextHolder sslContextHolder = ctx.sslContextHolder();
        SslHandler sslHandler = sslContextHolder.newHandler(ch.alloc());

        // 配置 SSL 参数
        sslHandler.engine().setUseClientMode(false);

        // 设置 SSL 握手超时（默认 10 秒），防止慢速握手攻击
        long handshakeTimeoutMillis = config.ssl != null && config.ssl.handshakeTimeout > 0
                ? config.ssl.handshakeTimeout
                : DEFAULT_HANDSHAKE_TIMEOUT_MILLIS;
        sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);

        pipeline.addLast("ssl", sslHandler);

        // 3. 日志 Handler（可选，用于调试）
        if (logger.isDebugEnabled()) {
            pipeline.addLast("logging", new LoggingHandler(LogLevel.DEBUG));
        }

        // 4. SSL 认证 Handler - SSL 握手完成后验证客户端证书
        pipeline.addLast("ssl-auth", new SSLAuthHandler(config, ctx.auditLogger(),
                ctx.connectionTracker(), ctx.rejectedCertTracker(), ctx.metrics()));

        // 5. HTTP 编解码器
        pipeline.addLast("http-codec", new HttpServerCodec());

        // 6. HTTP 消息聚合器（使用配置的最大请求大小）
        int maxRequestSize = config.performance != null && config.performance.maxRequestSize > 0
                ? config.performance.maxRequestSize
                : DEFAULT_MAX_REQUEST_SIZE;
        pipeline.addLast("http-aggregator", new HttpObjectAggregator(maxRequestSize));

        // 7. 请求 ID 生成 Handler - 为每个请求生成唯一标识
        pipeline.addLast("request-id", new RequestIdHandler());

        // 8. 指标收集 Handler
        if (config.metrics != null && config.metrics.enabled) {
            pipeline.addLast("metrics-collector", new MetricsCollectorHandler(ctx.metrics()));
        }

        // 9. 限流 Handler
        if (config.rateLimit != null && config.rateLimit.enabled) {
            pipeline.addLast("rate-limit", new RateLimitHandler(
                    ctx.rateLimiterManager(), ctx.metrics(), ctx.auditLogger()));
        }

        // 10. 访问日志 Handler（在代理 Handler 之前，记录请求和响应）
        if (config.accessLog != null && config.accessLog.enabled) {
            pipeline.addLast("access-log", new AccessLogHandler(config.accessLog, ctx.accessLogWriter()));
        }

        // 11. 代理前端 Handler - 核心代理逻辑
        pipeline.addLast("proxy-frontend", new ProxyFrontendHandler(
                config, connectionPool, ctx.metrics(), ctx.connectionTracker(),
                ctx.circuitBreaker(), ctx.loadBalancerManager()));

        logger.debug("Channel pipeline initialized for {}", ch.remoteAddress());
    }
}
