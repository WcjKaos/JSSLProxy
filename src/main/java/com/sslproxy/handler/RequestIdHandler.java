package com.sslproxy.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.AttributeKey;
import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求 ID 生成处理器
 * <p>
 * 为每个请求生成唯一的请求 ID，用于日志追踪和问题排查。
 * 请求 ID 会被添加到 MDC 中，使得所有相关日志都可以关联。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class RequestIdHandler extends ChannelInboundHandlerAdapter {

    /**
     * Channel Attribute Key 用于存储请求 ID
     */
    public static final AttributeKey<String> REQUEST_ID_KEY = AttributeKey.valueOf("requestId");

    /**
     * MDC 中的请求 ID 键名
     */
    public static final String MDC_REQUEST_ID = "requestId";

    /**
     * 请求计数器（用于生成简短的请求 ID）
     */
    private static final AtomicLong requestCounter = new AtomicLong(0);

    /**
     * 服务器启动时的时间戳前缀（用于保证重启后 ID 唯一性）
     */
    private static final String SERVER_ID = Long.toHexString(System.currentTimeMillis()).substring(4);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            // 生成请求 ID
            String requestId = generateRequestId();

            // 存储到 Channel Attribute
            ctx.channel().attr(REQUEST_ID_KEY).set(requestId);

            // 设置 MDC 用于日志关联
            MDC.put(MDC_REQUEST_ID, requestId);

            // 如果请求头中已经有 X-Request-ID，验证后使用客户端提供的
            HttpRequest request = (HttpRequest) msg;
            String clientRequestId = request.headers().get("X-Request-ID");
            if (clientRequestId != null && !clientRequestId.isEmpty() && isValidRequestId(clientRequestId)) {
                requestId = clientRequestId;
                ctx.channel().attr(REQUEST_ID_KEY).set(requestId);
                MDC.put(MDC_REQUEST_ID, requestId);
            } else {
                // 添加到请求头中，传递给后端
                request.headers().set("X-Request-ID", requestId);
            }
        }

        ctx.fireChannelRead(msg);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 清理 MDC
        MDC.remove(MDC_REQUEST_ID);
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // 清理 MDC
        MDC.remove(MDC_REQUEST_ID);
        super.exceptionCaught(ctx, cause);
    }

    /**
     * 生成唯一的请求 ID
     * <p>
     * 格式: {服务器ID}-{计数器}
     * 例如: a1b2-1234
     * </p>
     *
     * @return 请求 ID
     */
    private String generateRequestId() {
        long count = requestCounter.incrementAndGet();
        return SERVER_ID + "-" + Long.toHexString(count);
    }

    /**
     * 生成完整的 UUID 格式请求 ID（备用方法）
     *
     * @return UUID 格式的请求 ID
     */
    public static String generateUuidRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * 验证客户端提供的 Request ID 格式
     * 只允许字母、数字、连字符、下划线、点号，最大 128 字符
     */
    private static boolean isValidRequestId(String requestId) {
        if (requestId.length() > 128) {
            return false;
        }
        for (int i = 0; i < requestId.length(); i++) {
            char c = requestId.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取当前请求的 ID
     *
     * @param ctx Channel 上下文
     * @return 请求 ID，如果没有则返回 "unknown"
     */
    public static String getRequestId(ChannelHandlerContext ctx) {
        String requestId = ctx.channel().attr(REQUEST_ID_KEY).get();
        return requestId != null ? requestId : "unknown";
    }
}
