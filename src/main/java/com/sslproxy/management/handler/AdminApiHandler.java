package com.sslproxy.management.handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sslproxy.audit.AuditEventType;
import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ConfigWatcher;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.SslContextHolder;
import com.sslproxy.handler.MaxConnectionsHandler;
import com.sslproxy.metrics.ConnectionTracker;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.security.DynamicCertificateManager;
import com.sslproxy.security.RejectedCertTracker;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 管理 API 处理器
 * <p>
 * 处理 /admin/* 端点，提供运行时管理功能：
 * - POST /admin/whitelist - 添加证书到白名单
 * - DELETE /admin/whitelist - 从白名单移除证书
 * - POST /admin/blacklist - 添加证书到黑名单
 * - DELETE /admin/blacklist - 从黑名单移除证书
 * - GET /admin/connections - 获取当前连接信息
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class AdminApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(AdminApiHandler.class);

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final ProxyConfig config;
    private final ConfigWatcher configWatcher;
    private final DynamicCertificateManager dynamicCertManager;
    private final ConnectionTracker connectionTracker;
    private final RejectedCertTracker rejectedCertTracker;
    private final SslContextHolder sslContextHolder;
    private final AuditLogger auditLogger;
    private final ProxyMetrics metrics;

    public AdminApiHandler(ProxyConfig config, ConfigWatcher configWatcher,
                           DynamicCertificateManager dynamicCertManager,
                           ConnectionTracker connectionTracker,
                           RejectedCertTracker rejectedCertTracker,
                           SslContextHolder sslContextHolder,
                           AuditLogger auditLogger,
                           ProxyMetrics metrics) {
        this.config = config;
        this.configWatcher = configWatcher;
        this.dynamicCertManager = dynamicCertManager;
        this.connectionTracker = connectionTracker;
        this.rejectedCertTracker = rejectedCertTracker;
        this.sslContextHolder = sslContextHolder;
        this.auditLogger = auditLogger;
        this.metrics = metrics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        HttpMethod method = request.method();

        if (uri.startsWith("/admin/")) {
            handleAdminRequest(ctx, request, uri, method);
        } else {
            // 未匹配的请求返回 404
            sendNotFound(ctx, uri);
        }
    }

    private void handleAdminRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri, HttpMethod method) {
        try {
            if (uri.equals("/admin/whitelist")) {
                handleWhitelist(ctx, request, method);
            } else if (uri.equals("/admin/blacklist")) {
                handleBlacklist(ctx, request, method);
            } else if (uri.startsWith("/admin/connections")) {
                handleConnections(ctx, request, method);
            } else if (uri.equals("/admin/stats") && method == HttpMethod.GET) {
                handleStats(ctx);
            } else if (uri.startsWith("/admin/reload") && method == HttpMethod.POST) {
                handleReload(ctx, request);
            } else if (uri.equals("/admin/certs") && method == HttpMethod.GET) {
                handleCerts(ctx);
            } else if (uri.startsWith("/admin/drain")) {
                handleDrain(ctx, request, method);
            } else if (uri.startsWith("/admin/rejected-certs") && method == HttpMethod.GET) {
                handleRejectedCerts(ctx, request);
            } else {
                sendNotFound(ctx, uri);
            }
        } catch (Exception e) {
            logger.error("Error handling admin request: {}", e.getMessage(), e);
            sendError(ctx, "Internal server error: " + e.getMessage());
        }
    }

    /**
     * 处理白名单操作
     */
    private void handleWhitelist(ChannelHandlerContext ctx, FullHttpRequest request, HttpMethod method) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        Map<String, Object> response = new HashMap<>();
        DynamicCertificateManager certManager = dynamicCertManager;
        String sourceIp = ctx.channel().remoteAddress().toString();

        if (method == HttpMethod.POST) {
            String fingerprint = body.trim();
            boolean success = certManager.addToWhitelist(fingerprint);
            response.put("action", "add_whitelist");
            response.put("fingerprint", fingerprint);
            response.put("status", success ? "success" : "failed");
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);
            logger.info("Admin API: Add to whitelist: {}", fingerprint);
            auditAdmin(AuditEventType.ADMIN_WHITELIST_ADD, sourceIp,
                    "fingerprint=" + fingerprint, success ? "SUCCESS" : "FAILURE");
        } else if (method == HttpMethod.DELETE) {
            String fingerprint = body.trim();
            boolean success = certManager.removeFromWhitelist(fingerprint);
            response.put("action", "remove_whitelist");
            response.put("fingerprint", fingerprint);
            response.put("status", success ? "success" : "not_found");
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);
            logger.info("Admin API: Remove from whitelist: {}", fingerprint);
            auditAdmin(AuditEventType.ADMIN_WHITELIST_REMOVE, sourceIp,
                    "fingerprint=" + fingerprint, success ? "SUCCESS" : "NOT_FOUND");
        } else if (method == HttpMethod.GET) {
            response.put("whitelist", certManager.getWhitelist());
            response.put("count", certManager.getWhitelistSize());
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);
        } else {
            sendMethodNotAllowed(ctx);
        }
    }

    /**
     * 处理黑名单操作
     */
    private void handleBlacklist(ChannelHandlerContext ctx, FullHttpRequest request, HttpMethod method) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        Map<String, Object> response = new HashMap<>();
        DynamicCertificateManager certManager = dynamicCertManager;
        String sourceIp = ctx.channel().remoteAddress().toString();

        if (method == HttpMethod.POST) {
            String fingerprint = body.trim();
            boolean success = certManager.addToBlacklist(fingerprint);
            response.put("action", "add_blacklist");
            response.put("fingerprint", fingerprint);
            response.put("status", success ? "success" : "failed");
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);
            logger.info("Admin API: Add to blacklist: {}", fingerprint);
            auditAdmin(AuditEventType.ADMIN_BLACKLIST_ADD, sourceIp,
                    "fingerprint=" + fingerprint, success ? "SUCCESS" : "FAILURE");
        } else if (method == HttpMethod.DELETE) {
            String fingerprint = body.trim();
            boolean success = certManager.removeFromBlacklist(fingerprint);
            response.put("action", "remove_blacklist");
            response.put("fingerprint", fingerprint);
            response.put("status", success ? "success" : "not_found");
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);
            logger.info("Admin API: Remove from blacklist: {}", fingerprint);
            auditAdmin(AuditEventType.ADMIN_BLACKLIST_REMOVE, sourceIp,
                    "fingerprint=" + fingerprint, success ? "SUCCESS" : "NOT_FOUND");
        } else if (method == HttpMethod.GET) {
            response.put("blacklist", certManager.getBlacklist());
            response.put("count", certManager.getBlacklistSize());
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);
        } else {
            sendMethodNotAllowed(ctx);
        }
    }

    /**
     * 处理连接相关请求
     * GET /admin/connections — 获取连接信息
     * DELETE /admin/connections?fingerprint=XX — 终止指定指纹的连接
     */
    private void handleConnections(ChannelHandlerContext ctx, FullHttpRequest request, HttpMethod method) {
        com.sslproxy.metrics.ConnectionTracker tracker = connectionTracker;

        if (method == HttpMethod.GET) {
            Map<String, Object> response = new HashMap<>();
            response.put("activeConnections", tracker.getActiveCount());
            response.put("timestamp", System.currentTimeMillis());
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);

        } else if (method == HttpMethod.DELETE) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            List<String> fps = decoder.parameters().get("fingerprint");
            if (fps == null || fps.isEmpty() || fps.get(0).trim().isEmpty()) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Missing required parameter: fingerprint");
                sendJsonResponse(ctx, HttpResponseStatus.BAD_REQUEST, err);
                return;
            }

            String fingerprint = fps.get(0).trim();
            if (!isValidFingerprint(fingerprint)) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Invalid fingerprint format. Expected SHA-256 hex string (e.g. AA:BB:CC:...)");
                sendJsonResponse(ctx, HttpResponseStatus.BAD_REQUEST, err);
                return;
            }

            int terminated = tracker.terminateByFingerprint(fingerprint);

            Map<String, Object> response = new HashMap<>();
            response.put("action", "terminate_connections");
            response.put("fingerprint", fingerprint.toUpperCase());
            response.put("terminated", terminated);
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);

            String sourceIp = ctx.channel().remoteAddress().toString();
            logger.info("Admin API: terminated {} connections for fingerprint {}", terminated, fingerprint);
            auditAdmin(AuditEventType.ADMIN_CONNECTION_TERMINATE, sourceIp,
                    "fingerprint=" + fingerprint + ", terminated=" + terminated, "SUCCESS");
        } else {
            sendMethodNotAllowed(ctx);
        }
    }

    /**
     * 验证 SHA-256 指纹格式
     * 合法格式：32 组 2 位十六进制数，冒号分隔（AA:BB:CC:...，共 95 字符）
     * 也接受无分隔符的 64 位十六进制
     */
    private boolean isValidFingerprint(String fingerprint) {
        if (fingerprint == null) return false;
        String stripped = fingerprint.replace(":", "").replace("-", "");
        return stripped.length() == 64 && stripped.matches("[A-Fa-f0-9]+");
    }

    /**
     * 显式触发配置或证书热加载
     * POST /admin/reload?target=config|certs|all
     */
    private void handleReload(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> targets = decoder.parameters().get("target");
        String target = (targets != null && !targets.isEmpty()) ? targets.get(0) : "all";

        Map<String, Object> response = new HashMap<>();
        boolean success = true;

        if ("config".equals(target) || "all".equals(target)) {
            if (configWatcher != null) {
                configWatcher.triggerReload();
                response.put("config", "reloaded");
            } else {
                response.put("config", "no_watcher");
            }
        }

        if ("certs".equals(target) || "all".equals(target)) {
            if (sslContextHolder != null) {
                try {
                    sslContextHolder.reload();
                    response.put("certs", "reloaded");
                } catch (Exception e) {
                    logger.error("Failed to reload SSL context via API: {}", e.getMessage(), e);
                    response.put("certs", "failed: " + e.getMessage());
                    success = false;
                }
            } else {
                response.put("certs", "not_initialized");
            }
        }

        response.put("success", success);
        response.put("target", target);
        logger.info("Admin API: reload triggered for target={}, success={}", target, success);
        sendJsonResponse(ctx, success ? HttpResponseStatus.OK : HttpResponseStatus.INTERNAL_SERVER_ERROR, response);

        String sourceIp = ctx.channel().remoteAddress().toString();
        auditAdmin(AuditEventType.ADMIN_RELOAD, sourceIp,
                "target=" + target, success ? "SUCCESS" : "FAILURE");
    }

    /**
     * 获取统计信息
     */
    private void handleStats(ChannelHandlerContext ctx) {
        Map<String, Object> response = new HashMap<>();

        if (metrics.isEnabled()) {
            response.put("activeConnections", metrics.getActiveConnections());
            response.put("bytesReceived", metrics.getBytesReceived());
            response.put("bytesSent", metrics.getBytesSent());
            response.put("uptimeSeconds", metrics.getUptimeSeconds());
        } else {
            response.put("message", "Metrics not available");
        }
        response.put("timestamp", System.currentTimeMillis());

        sendJsonResponse(ctx, HttpResponseStatus.OK, response);
    }

    /**
     * 证书自省端点
     * GET /admin/certs
     */
    private void handleCerts(ChannelHandlerContext ctx) {
        Map<String, Object> response = new HashMap<>();
        if (sslContextHolder != null) {
            response.put("certificates", sslContextHolder.getCertificateInfo());
            response.put("reloadCount", sslContextHolder.getReloadCount());
            response.put("lastReloadTime", sslContextHolder.getLastReloadTime());
            response.put("status", sslContextHolder.getCertificateStatus());
        } else {
            response.put("certificates", List.of());
            response.put("status", "NOT_INITIALIZED");
        }
        response.put("timestamp", System.currentTimeMillis());
        sendJsonResponse(ctx, HttpResponseStatus.OK, response);
    }

    /**
     * Drain 模式端点
     * POST /admin/drain?timeout=30
     * DELETE /admin/drain
     */
    private void handleDrain(ChannelHandlerContext ctx, FullHttpRequest request, HttpMethod method) {
        Map<String, Object> response = new HashMap<>();
        String sourceIp = ctx.channel().remoteAddress().toString();

        if (method == HttpMethod.POST) {
            QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
            List<String> timeoutParams = decoder.parameters().get("timeout");
            int timeout = 0;
            if (timeoutParams != null && !timeoutParams.isEmpty()) {
                try {
                    timeout = Integer.parseInt(timeoutParams.get(0));
                } catch (NumberFormatException e) {
                    response.put("error", "Invalid timeout value");
                    sendJsonResponse(ctx, HttpResponseStatus.BAD_REQUEST, response);
                    return;
                }
            }

            MaxConnectionsHandler.startDrain(timeout);
            response.put("action", "drain_start");
            response.put("timeout", timeout);
            response.put("status", "draining");
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);
            logger.info("Admin API: drain started (timeout={}s)", timeout);
            auditAdmin(AuditEventType.ADMIN_DRAIN_START, sourceIp,
                    "timeout=" + timeout, "SUCCESS");
        } else if (method == HttpMethod.DELETE) {
            MaxConnectionsHandler.cancelDrain();
            response.put("action", "drain_cancel");
            response.put("status", "normal");
            sendJsonResponse(ctx, HttpResponseStatus.OK, response);
            logger.info("Admin API: drain cancelled");
            auditAdmin(AuditEventType.ADMIN_DRAIN_CANCEL, sourceIp, null, "SUCCESS");
        } else {
            sendMethodNotAllowed(ctx);
        }
    }

    /**
     * mTLS 调试端点：被拒绝的客户端证书
     * GET /admin/rejected-certs?limit=50
     */
    private void handleRejectedCerts(ChannelHandlerContext ctx, FullHttpRequest request) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        List<String> limitParams = decoder.parameters().get("limit");
        int limit = 50;
        if (limitParams != null && !limitParams.isEmpty()) {
            try {
                limit = Math.max(1, Math.min(500, Integer.parseInt(limitParams.get(0))));
            } catch (NumberFormatException ignored) {
                // use default
            }
        }

        RejectedCertTracker tracker = rejectedCertTracker;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RejectedCertTracker.RejectedCertRecord record : tracker.getRecords(limit)) {
            entries.add(record.toMap());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("rejectedCerts", entries);
        response.put("count", entries.size());
        response.put("totalTracked", tracker.getSize());
        response.put("timestamp", System.currentTimeMillis());
        sendJsonResponse(ctx, HttpResponseStatus.OK, response);
    }

    /**
     * 记录管理 API 审计日志
     */
    private void auditAdmin(AuditEventType eventType, String sourceIp, String detail, String result) {
        Map<String, Object> details = new HashMap<>();
        details.put("sourceIp", sourceIp);
        if (detail != null) {
            details.put("detail", detail);
        }
        details.put("result", result);
        auditLogger.log(eventType, details);
    }

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

    private void sendNotFound(ChannelHandlerContext ctx, String uri) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Not Found");
        response.put("path", uri);
        sendJsonResponse(ctx, HttpResponseStatus.NOT_FOUND, response);
    }

    private void sendMethodNotAllowed(ChannelHandlerContext ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", "Method Not Allowed");
        sendJsonResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, response);
    }

    private void sendError(ChannelHandlerContext ctx, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", message);
        sendJsonResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error in admin API handler: {}", cause.getMessage());
        ctx.close();
    }
}
