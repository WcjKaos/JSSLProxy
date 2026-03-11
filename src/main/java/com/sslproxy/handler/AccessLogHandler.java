package com.sslproxy.handler;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig.AccessLog;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;

/**
 * HTTP 访问日志处理器
 * <p>
 * 记录每个 HTTP 请求的详细信息，类似 Nginx access.log。
 * 支持 JSON 和 Combined 两种日志格式，支持异步写入。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class AccessLogHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(AccessLogHandler.class);
    private static final Logger accessLogger = LoggerFactory.getLogger("ACCESS");

    /** Combined Log Format 日期格式 */
    private static final DateTimeFormatter CLF_DATE_FORMAT = DateTimeFormatter
            .ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    /** 请求开始时间属性 */
    private static final AttributeKey<Long> REQUEST_START_TIME = AttributeKey.valueOf("requestStartTime");
    /** 请求信息属性 */
    private static final AttributeKey<RequestInfo> REQUEST_INFO = AttributeKey.valueOf("requestInfo");
    /** 响应状态属性 */
    private static final AttributeKey<Integer> RESPONSE_STATUS = AttributeKey.valueOf("responseStatus");
    /** 响应体大小属性 */
    private static final AttributeKey<Long> RESPONSE_BODY_SIZE = AttributeKey.valueOf("responseBodySize");

    private final AccessLog config;
    private final AccessLogWriter asyncWriter;

    /**
     * 请求信息内部类
     */
    private static class RequestInfo {
        final String method;
        final String uri;
        final String protocol;
        final String userAgent;
        final String referer;
        final String clientIp;
        final String traceId;

        RequestInfo(String method, String uri, String protocol, String userAgent,
                    String referer, String clientIp, String traceId) {
            this.method = method;
            this.uri = uri;
            this.protocol = protocol;
            this.userAgent = userAgent;
            this.referer = referer;
            this.clientIp = clientIp;
            this.traceId = traceId;
        }
    }

    /**
     * 构造访问日志处理器
     *
     * @param config      访问日志配置
     * @param asyncWriter 异步写入器（可为 null，为 null 时使用 SLF4J）
     */
    public AccessLogHandler(AccessLog config, AccessLogWriter asyncWriter) {
        this.config = config;
        this.asyncWriter = asyncWriter;
    }

    /**
     * 构造访问日志处理器（不使用异步写入器）
     *
     * @param config 访问日志配置
     */
    public AccessLogHandler(AccessLog config) {
        this(config, null);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;

            // 记录请求开始时间
            ctx.channel().attr(REQUEST_START_TIME).set(System.currentTimeMillis());

            // 提取请求信息
            String clientIp = extractClientIp(ctx);
            String traceId = MDC.get(AuditLogger.MDC_REQUEST_ID);

            RequestInfo requestInfo = new RequestInfo(
                    request.method().name(),
                    request.uri(),
                    request.protocolVersion().text(),
                    request.headers().get(HttpHeaderNames.USER_AGENT, "-"),
                    request.headers().get(HttpHeaderNames.REFERER, "-"),
                    clientIp,
                    traceId != null ? traceId : "-"
            );
            ctx.channel().attr(REQUEST_INFO).set(requestInfo);

            // 初始化响应体大小
            ctx.channel().attr(RESPONSE_BODY_SIZE).set(0L);
        }

        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            ctx.channel().attr(RESPONSE_STATUS).set(response.status().code());
        }

        if (msg instanceof LastHttpContent) {
            LastHttpContent lastContent = (LastHttpContent) msg;

            // 累加响应体大小
            Long currentSize = ctx.channel().attr(RESPONSE_BODY_SIZE).get();
            if (currentSize != null) {
                ctx.channel().attr(RESPONSE_BODY_SIZE).set(currentSize + lastContent.content().readableBytes());
            }

            // 记录访问日志
            promise.addListener(future -> {
                if (future.isSuccess()) {
                    logAccess(ctx);
                }
            });
        }

        super.write(ctx, msg, promise);
    }

    /**
     * 记录访问日志
     */
    private void logAccess(ChannelHandlerContext ctx) {
        Long startTime = ctx.channel().attr(REQUEST_START_TIME).get();
        RequestInfo requestInfo = ctx.channel().attr(REQUEST_INFO).get();
        Integer status = ctx.channel().attr(RESPONSE_STATUS).get();
        Long bodySize = ctx.channel().attr(RESPONSE_BODY_SIZE).get();

        if (requestInfo == null) {
            return;
        }

        long latencyMs = startTime != null ? System.currentTimeMillis() - startTime : 0;
        int statusCode = status != null ? status : 0;
        long responseSize = bodySize != null ? bodySize : 0;

        String logLine;
        if ("COMBINED".equalsIgnoreCase(config.format)) {
            logLine = formatCombinedLog(requestInfo, statusCode, responseSize, latencyMs);
        } else {
            logLine = formatJsonLog(requestInfo, statusCode, responseSize, latencyMs);
        }

        // 输出日志
        if (config.path != null && asyncWriter != null) {
            asyncWriter.write(logLine);
        } else {
            accessLogger.info(logLine);
        }

        // 清理属性
        ctx.channel().attr(REQUEST_START_TIME).set(null);
        ctx.channel().attr(REQUEST_INFO).set(null);
        ctx.channel().attr(RESPONSE_STATUS).set(null);
        ctx.channel().attr(RESPONSE_BODY_SIZE).set(null);
    }

    /**
     * 格式化 Combined Log Format
     * 格式: client_ip - - [timestamp] "method uri protocol" status body_bytes "referer" "user_agent"
     */
    private String formatCombinedLog(RequestInfo req, int status, long bodySize, long latencyMs) {
        String timestamp = CLF_DATE_FORMAT.format(Instant.now());
        return String.format("%s - - [%s] \"%s %s %s\" %d %d \"%s\" \"%s\" %dms",
                req.clientIp,
                timestamp,
                req.method,
                req.uri,
                req.protocol,
                status,
                bodySize,
                req.referer,
                req.userAgent,
                latencyMs);
    }

    /**
     * 格式化 JSON 日志
     */
    private String formatJsonLog(RequestInfo req, int status, long bodySize, long latencyMs) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"@timestamp\":\"").append(Instant.now().toString()).append("\"");
        json.append(",\"client_ip\":\"").append(escapeJson(req.clientIp)).append("\"");
        json.append(",\"method\":\"").append(req.method).append("\"");
        json.append(",\"uri\":\"").append(escapeJson(req.uri)).append("\"");
        json.append(",\"protocol\":\"").append(req.protocol).append("\"");
        json.append(",\"status\":").append(status);
        json.append(",\"body_bytes\":").append(bodySize);
        json.append(",\"latency_ms\":").append(latencyMs);
        json.append(",\"user_agent\":\"").append(escapeJson(req.userAgent)).append("\"");
        json.append(",\"referer\":\"").append(escapeJson(req.referer)).append("\"");
        if (!"-".equals(req.traceId)) {
            json.append(",\"trace_id\":\"").append(escapeJson(req.traceId)).append("\"");
        }
        json.append("}");
        return json.toString();
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 提取客户端 IP
     */
    private String extractClientIp(ChannelHandlerContext ctx) {
        String remoteAddress = ctx.channel().remoteAddress() != null
                ? ctx.channel().remoteAddress().toString()
                : "unknown";

        // 移除开头的斜杠和端口
        if (remoteAddress.startsWith("/")) {
            remoteAddress = remoteAddress.substring(1);
        }
        int colonIndex = remoteAddress.lastIndexOf(':');
        if (colonIndex > 0) {
            remoteAddress = remoteAddress.substring(0, colonIndex);
        }

        return remoteAddress;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.debug("Exception in access log handler: {}", cause.getMessage());
        super.exceptionCaught(ctx, cause);
    }

}
