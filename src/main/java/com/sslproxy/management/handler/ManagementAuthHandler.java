package com.sslproxy.management.handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.audit.AuditEventType;
import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;

/**
 * 管理端点鉴权处理器
 * <p>
 * 默认仅保护 /admin/* 端点，避免未授权管理操作。
 * Token 建议通过环境变量 MANAGEMENT_TOKEN 注入。
 * </p>
 */
public class ManagementAuthHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ManagementAuthHandler.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_PREFIX = "/admin/";

    private final ProxyConfig config;
    private final AuditLogger auditLogger;

    public ManagementAuthHandler(ProxyConfig config, AuditLogger auditLogger) {
        this.config = config;
        this.auditLogger = auditLogger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        String uri = request.uri();
        if (uri == null || !uri.startsWith(ADMIN_PREFIX)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (config == null || config.management == null || !config.management.authEnabled) {
            logger.warn("Management auth disabled; allowing admin request from {}", ctx.channel().remoteAddress());
            ctx.fireChannelRead(msg);
            return;
        }

        String configuredToken = config.management.authToken;
        if (configuredToken == null || configuredToken.isBlank()) {
            logger.warn("MANAGEMENT_TOKEN not configured; rejecting admin request from {}", ctx.channel().remoteAddress());
            reject(ctx, HttpResponseStatus.FORBIDDEN, "Admin API disabled (missing MANAGEMENT_TOKEN)");
            ReferenceCountUtil.release(msg);
            return;
        }

        String providedToken = extractToken(request);
        if (providedToken == null || !constantTimeEquals(configuredToken, providedToken)) {
            String sourceIp = ctx.channel().remoteAddress().toString();
            logger.warn("Unauthorized admin API access from {}: {}", sourceIp, uri);
            auditUnauthorized(sourceIp, uri);
            reject(ctx, HttpResponseStatus.UNAUTHORIZED, "Unauthorized");
            ReferenceCountUtil.release(msg);
            return;
        }

        ctx.fireChannelRead(msg);
    }

    private String extractToken(FullHttpRequest request) {
        String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        String token = request.headers().get("X-Admin-Token");
        return token != null ? token.trim() : null;
    }

    /**
     * 恒定时间字符串比较，防止时序侧信道攻击
     */
    private static boolean constantTimeEquals(String expected, String provided) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private void auditUnauthorized(String sourceIp, String uri) {
        java.util.Map<String, Object> details = new java.util.HashMap<>();
        details.put("sourceIp", sourceIp);
        details.put("uri", uri);
        details.put("result", "FAILURE");
        auditLogger.log(AuditEventType.ADMIN_UNAUTHORIZED, details);
    }

    private void reject(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(bytes)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}

