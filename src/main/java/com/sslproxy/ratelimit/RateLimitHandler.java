package com.sslproxy.ratelimit;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.ratelimit.RateLimiterManager.RateLimitResult;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * 限流处理器
 * <p>
 * 在 Pipeline 中执行限流检查。
 * 如果请求被限流，返回 429 Too Many Requests。
 * </p>
 * <p>
 * 使用 ChannelInboundHandlerAdapter 而非 SimpleChannelInboundHandler，
 * 以便正确管理 ByteBuf 引用计数：
 * - 传递给下游时不需要额外 retain()
 * - 被限流时需要手动 release()
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class RateLimitHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitHandler.class);

    /** 客户端证书指纹属性键 */
    public static final AttributeKey<String> CLIENT_FINGERPRINT_KEY =
            AttributeKey.valueOf("clientFingerprint");

    private final RateLimiterManager rateLimiterManager;
    private final ProxyMetrics metrics;
    private final AuditLogger auditLogger;

    public RateLimitHandler(RateLimiterManager rateLimiterManager, ProxyMetrics metrics, AuditLogger auditLogger) {
        this.rateLimiterManager = rateLimiterManager;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 检查所有 HttpRequest（包括 FullHttpRequest 和流式 HttpRequest），防止分块请求绕过限流
        if (!(msg instanceof HttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }

        RateLimiterManager rateLimiter = rateLimiterManager;

        if (rateLimiter == null || !rateLimiter.isEnabled()) {
            // 限流未启用，直接传递（不需要 retain，因为我们不消费它）
            ctx.fireChannelRead(msg);
            return;
        }

        // 获取客户端证书指纹（如果有）
        String fingerprint = ctx.channel().attr(CLIENT_FINGERPRINT_KEY).get();

        // 执行双层限流检查
        RateLimitResult result = rateLimiter.tryConsume(fingerprint);

        if (result == RateLimitResult.ALLOWED) {
            // 允许请求，传递给下一个处理器
            ctx.fireChannelRead(msg);
        } else {
            // 被限流，发送 429 响应并释放请求
            try {
                sendRateLimitResponse(ctx, result);
            } finally {
                // 手动释放被拦截的请求
                ReferenceCountUtil.release(msg);
            }
        }
    }

    /**
     * 发送限流响应
     *
     * @param ctx    Channel 上下文
     * @param result 限流结果
     */
    private void sendRateLimitResponse(ChannelHandlerContext ctx, RateLimitResult result) {
        if (result == RateLimitResult.GLOBAL_LIMITED) {
            metrics.incrementGlobalRateLimited();
        } else if (result == RateLimitResult.CLIENT_LIMITED) {
            metrics.incrementClientRateLimited();
        }

        // 获取客户端标识用于审计日志
        String fingerprint = ctx.channel().attr(CLIENT_FINGERPRINT_KEY).get();
        String clientAddress = ctx.channel().remoteAddress() != null
                ? ctx.channel().remoteAddress().toString()
                : "unknown";

        // 记录审计日志
        boolean isGlobal = result == RateLimitResult.GLOBAL_LIMITED;
        String clientId = fingerprint != null ? fingerprint.substring(0, Math.min(16, fingerprint.length())) + "..." : clientAddress;
        auditLogger.logRateLimited(isGlobal, clientId);

        // 构造响应
        String message = result == RateLimitResult.GLOBAL_LIMITED
                ? "Rate limit exceeded (global)"
                : "Rate limit exceeded (client)";

        byte[] content = message.getBytes(StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.TOO_MANY_REQUESTS,
                Unpooled.wrappedBuffer(content)
        );

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        // Retry-After 建议客户端等待 1 秒后重试
        response.headers().set("Retry-After", "1");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);

        logger.debug("Rate limited request: {}", result);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Error in rate limit handler: {}", cause.getMessage());
        super.exceptionCaught(ctx, cause);
    }
}
