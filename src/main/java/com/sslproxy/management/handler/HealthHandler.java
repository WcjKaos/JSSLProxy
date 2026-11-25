package com.sslproxy.management.handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sslproxy.client.BackendConnectionPool;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.SslContextHolder;
import com.sslproxy.handler.MaxConnectionsHandler;
import com.sslproxy.loadbalance.LoadBalancerManager;
import com.sslproxy.metrics.ProxyMetrics;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查处理器
 * <p>
 * 处理以下端点：
 * - /health - 基本健康检查
 * - /ready  - 就绪检查
 * - /admin/info - 服务器信息
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class HealthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(HealthHandler.class);

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final ProxyConfig config;
    private final BackendConnectionPool connectionPool;
    private final ProxyMetrics metrics;
    private final LoadBalancerManager loadBalancerManager;
    private final SslContextHolder sslContextHolder;

    public HealthHandler(ProxyConfig config, BackendConnectionPool connectionPool,
                         ProxyMetrics metrics, LoadBalancerManager loadBalancerManager,
                         SslContextHolder sslContextHolder) {
        this.config = config;
        this.connectionPool = connectionPool;
        this.metrics = metrics;
        this.loadBalancerManager = loadBalancerManager;
        this.sslContextHolder = sslContextHolder;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();

        if ("/health".equals(uri)) {
            handleHealth(ctx);
        } else if ("/ready".equals(uri)) {
            handleReady(ctx);
        } else if ("/admin/info".equals(uri)) {
            handleInfo(ctx);
        } else {
            // 传递给下一个处理器
            ctx.fireChannelRead(request.retain());
        }
    }

    /**
     * 健康检查端点
     * 总是返回 200，表示服务存活
     */
    private void handleHealth(ChannelHandlerContext ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", System.currentTimeMillis());

        sendJsonResponse(ctx, HttpResponseStatus.OK, response);
        logger.debug("Health check: UP");
    }

    /**
     * 就绪检查端点
     * 检查服务是否已准备好接收流量：
     * - Drain 模式 → 503
     * - 后端全部 DOWN → 503
     * - SSL 证书过期 → 503
     */
    private void handleReady(ChannelHandlerContext ctx) {
        if (MaxConnectionsHandler.isDraining() || loadBalancerManager == null) {
            sendReadinessResponse(ctx, Map.of());
            return;
        }
        loadBalancerManager.checkReadiness().whenComplete((backends, failure) -> {
            Runnable response = () -> {
                if (ctx.channel().isActive()) {
                    sendReadinessResponse(ctx, failure == null ? backends : Map.of());
                }
            };
            if (ctx.executor().inEventLoop()) {
                response.run();
            } else {
                ctx.executor().execute(response);
            }
        });
    }

    private void sendReadinessResponse(ChannelHandlerContext ctx, Map<String, Boolean> backends) {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> checks = new HashMap<>();

        // Drain 模式检查
        if (MaxConnectionsHandler.isDraining()) {
            checks.put("drain", "ACTIVE");
            response.put("status", "DRAINING");
            response.put("checks", checks);
            response.put("timestamp", System.currentTimeMillis());
            sendJsonResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, response);
            logger.debug("Ready check: DRAINING");
            return;
        }

        boolean ready = false;

        // 后端健康检查（通过 LoadBalancerManager 获取实时状态）
        LoadBalancerManager lbManager = loadBalancerManager;
        if (lbManager != null) {
            long healthy = backends.values().stream().filter(Boolean.TRUE::equals).count();
            long total = backends.size();

            Map<String, Object> backendStatus = new HashMap<>();
            backendStatus.put("healthy", healthy);
            backendStatus.put("total", total);
            backendStatus.put("status", healthy == 0 ? "DOWN" : healthy == total ? "UP" : "PARTIAL");
            backends.forEach((key, available) -> backendStatus.put(key, available ? "UP" : "DOWN"));
            checks.put("backendTarget", backendStatus);
            ready = healthy > 0;
        } else {
            // 无 LoadBalancerManager 时显示配置信息
            Map<String, Object> backendInfo = new HashMap<>();
            backendInfo.put("host", config.target.host);
            backendInfo.put("port", config.target.port);
            backendInfo.put("status", "UNKNOWN");
            checks.put("backendTarget", backendInfo);
        }

        // SSL 上下文 + 证书有效性检查
        if (sslContextHolder != null) {
            String certStatus = sslContextHolder.getCertificateStatus();
            checks.put("sslContext", Map.of("status", certStatus));
            if ("EXPIRED".equals(certStatus) || "DOWN".equals(certStatus)) {
                ready = false;
            }
        } else {
            checks.put("sslContext", Map.of("status", "DOWN"));
            ready = false;
        }

        // 指标系统
        checks.put("metrics", metrics.isEnabled() ? "UP" : "DISABLED");

        response.put("status", ready ? "UP" : "DOWN");
        response.put("checks", checks);
        response.put("timestamp", System.currentTimeMillis());

        HttpResponseStatus status = ready ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE;
        sendJsonResponse(ctx, status, response);
        logger.debug("Ready check: {}", ready ? "UP" : "DOWN");
    }

    /**
     * 服务器信息端点
     */
    private void handleInfo(ChannelHandlerContext ctx) {
        Map<String, Object> response = new HashMap<>();

        // 基本信息
        response.put("name", "JSSLProxy");
        String version = getClass().getPackage() != null
                ? getClass().getPackage().getImplementationVersion()
                : null;
        response.put("version", version != null ? version : "dev");
        response.put("configHash", config.computeConfigHash());

        // 服务器配置
        Map<String, Object> server = new HashMap<>();
        server.put("host", config.server.host);
        server.put("port", config.server.port);
        response.put("server", server);

        // 目标配置
        Map<String, Object> target = new HashMap<>();
        target.put("host", config.target.host);
        target.put("port", config.target.port);
        target.put("protocol", config.target.protocol);
        response.put("target", target);

        // 运行时信息
        Map<String, Object> runtime = new HashMap<>();
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        runtime.put("maxMemoryMB", Runtime.getRuntime().maxMemory() / (1024 * 1024));

        if (metrics.isEnabled()) {
            runtime.put("uptimeSeconds", metrics.getUptimeSeconds());
            runtime.put("activeConnections", metrics.getActiveConnections());
        }
        response.put("runtime", runtime);

        response.put("timestamp", System.currentTimeMillis());

        sendJsonResponse(ctx, HttpResponseStatus.OK, response);
        logger.debug("Info endpoint accessed");
    }

    /**
     * 发送 JSON 响应
     */
    private void sendJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(bytes)
        );

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error in health handler: {}", cause.getMessage());
        ctx.close();
    }
}
