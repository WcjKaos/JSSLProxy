package com.sslproxy.handler;

import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateRevokedException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.audit.AuditEventType;
import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.metrics.ConnectionTracker;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.ratelimit.RateLimitHandler;
import com.sslproxy.security.RejectedCertTracker;
import com.sslproxy.util.CertificateUtil;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SslHandshakeTimeoutException;

/**
 * SSL 握手后处理器
 * <p>
 * 在 SSL/TLS 握手完成后执行的业务扩展点。
 * 主要功能：
 * <ul>
 *   <li>记录客户端连接信息和证书详情（用于审计日志）</li>
 *   <li>提供业务规则验证的扩展点（如权限检查、IP白名单、时段限制等）</li>
 * </ul>
 * 注意：核心的证书验证（过期、白名单/黑名单、证书链）已在 ClientAuthenticator 中完成。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class SSLAuthHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(SSLAuthHandler.class);

    private final ProxyConfig config;
    private final AuditLogger auditLogger;
    private final ConnectionTracker connectionTracker;
    private final RejectedCertTracker rejectedCertTracker;
    private final ProxyMetrics metrics;
    private long handshakeStartTime;

    /**
     * 构造 SSL 认证处理器
     *
     * @param config              代理配置
     * @param auditLogger         审计日志
     * @param connectionTracker   连接追踪器
     * @param rejectedCertTracker 被拒绝证书追踪器
     * @param metrics             代理指标
     */
    public SSLAuthHandler(ProxyConfig config, AuditLogger auditLogger,
                          ConnectionTracker connectionTracker,
                          RejectedCertTracker rejectedCertTracker,
                          ProxyMetrics metrics) {
        this.config = config;
        this.auditLogger = auditLogger;
        this.connectionTracker = connectionTracker;
        this.rejectedCertTracker = rejectedCertTracker;
        this.metrics = metrics;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // 记录握手开始时间
        handshakeStartTime = System.currentTimeMillis();
        super.channelActive(ctx);
    }

    /**
     * 处理用户事件
     * <p>
     * 监听 SslHandshakeCompletionEvent 事件，在 SSL 握手完成后执行证书验证。
     * </p>
     * 
     * @param ctx Channel 上下文
     * @param evt 事件对象
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof SslHandshakeCompletionEvent event) {

            if (event.isSuccess()) {
                // 记录 SSL 握手指标
                recordSslHandshakeMetrics(true, null);
                handleSuccessfulHandshake(ctx);
            } else {
                // 记录 SSL 握手失败指标（含原因分类）
                recordSslHandshakeMetrics(false, event.cause());
                handleFailedHandshake(ctx, event.cause());
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 处理成功的 SSL 握手
     * <p>
     * 提取客户端证书信息，记录日志，并验证证书合法性。
     * </p>
     * 
     * @param ctx Channel 上下文
     */
    private void handleSuccessfulHandshake(ChannelHandlerContext ctx) {
        String clientAddress = ctx.channel().remoteAddress().toString();
        logger.debug("SSL handshake completed for {}", clientAddress);

        try {
            SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
            if (sslHandler == null) {
                logger.warn("SslHandler not found in pipeline for {}", clientAddress);
                ctx.close();
                return;
            }

            SSLSession session = sslHandler.engine().getSession();
            
            // 根据配置的 clientAuth 模式处理证书
            String clientAuthMode = config.ssl.clientAuth;
            
            if ("NONE".equalsIgnoreCase(clientAuthMode)) {
                // 不需要客户端证书
                logger.info("Client connected (no client cert required): {}", clientAddress);
                // 注册连接到追踪器（无证书指纹）
                connectionTracker.register(ctx.channel());
                ctx.pipeline().remove(this);
                return;
            }

            // 尝试获取客户端证书
            Certificate[] peerCerts = null;
            try {
                peerCerts = session.getPeerCertificates();
            } catch (SSLPeerUnverifiedException e) {
                if ("REQUIRED".equalsIgnoreCase(clientAuthMode)) {
                    // 必需模式下，没有证书则拒绝
                    logger.warn("Client certificate required but not provided: {}", clientAddress);
                    metrics.incrementSslHandshakeFailures("no_cert");
                    rejectedCertTracker.record(
                            clientAddress, null, null, "no_cert: client certificate required but not provided");
                    ctx.close();
                    return;
                } else if ("OPTIONAL".equalsIgnoreCase(clientAuthMode)) {
                    // 可选模式下，没有证书也允许通过
                    logger.info("Client connected without certificate (optional mode): {}", clientAddress);
                    // 注册连接到追踪器（无证书指纹）
                    connectionTracker.register(ctx.channel());
                    ctx.pipeline().remove(this);
                    return;
                }
            }

            // 验证证书
            if (peerCerts != null && peerCerts.length > 0) {
                X509Certificate clientCert = (X509Certificate) peerCerts[0];
                validateClientCertificate(ctx, clientCert, clientAddress);
            }

        } catch (Exception e) {
            logger.error("Error during SSL authentication for {}: {}", clientAddress, e.getMessage(), e);
            ctx.close();
        }
    }

    /**
     * 处理客户端证书信息（业务扩展点）
     * <p>
     * 核心证书验证（过期、白名单/黑名单、证书链）已在 ClientAuthenticator 中完成。
     * 此方法用于：
     * <ul>
     *   <li>记录详细的审计日志</li>
     *   <li>执行业务规则验证（不影响 TLS 握手的额外检查）</li>
     * </ul>
     * </p>
     * 
     * @param ctx Channel 上下文
     * @param clientCert 客户端证书
     * @param clientAddress 客户端地址
     */
    private void validateClientCertificate(ChannelHandlerContext ctx, X509Certificate clientCert, String clientAddress) {
        String summary = CertificateUtil.getCertificateSummary(clientCert);

        // 记录详细的客户端信息（审计日志）
        String commonName = CertificateUtil.getCommonName(clientCert);
        String fingerprint = CertificateUtil.getCertificateFingerprint(clientCert);

        logger.info("✓ Client connected: CN={}, Fingerprint={}, Address={}",
                commonName,
                fingerprint != null ? fingerprint.substring(0, Math.min(16, fingerprint.length())) + "..." : "unknown",
                clientAddress);

        // 设置 MDC 上下文用于后续日志
        AuditLogger.setContext(null, clientAddress);
        AuditLogger.setClientCertCN(commonName);

        // 记录审计日志 - 证书验证成功
        auditLogger.logCertValidated(commonName, fingerprint);

        // 注册连接到追踪器
        connectionTracker.register(ctx.channel(), fingerprint);

        // 存储指纹到通道属性，供限流使用
        if (fingerprint != null) {
            ctx.channel().attr(RateLimitHandler.CLIENT_FINGERPRINT_KEY).set(fingerprint);
        }

        // ============ 业务扩展点 ============
        // 在这里可以添加自定义的业务验证逻辑（不影响 TLS 握手）：
        // 
        // 示例 1: 检查用户权限
        // if (!hasPermission(commonName)) {
        //     logger.warn("Client {} has no permission", commonName);
        //     ctx.close();
        //     return;
        // }
        //
        // 示例 2: IP 白名单检查
        // if (!isAllowedIp(clientAddress)) {
        //     logger.warn("Client IP {} not in whitelist", clientAddress);
        //     ctx.close();
        //     return;
        // }
        //
        // 示例 3: 访问时段限制
        // if (!isWithinAllowedTimeWindow()) {
        //     logger.warn("Client access outside allowed time window");
        //     ctx.close();
        //     return;
        // }
        //
        // 示例 4: 记录到数据库
        // auditLog.recordClientConnection(commonName, fingerprint, clientAddress);
        //
        // 示例 5: 调用外部 API 验证
        // if (!externalAuthService.validate(commonName)) {
        //     logger.warn("External auth failed for {}", commonName);
        //     ctx.close();
        //     return;
        // }
        // ===================================

        // 处理完成，移除此 Handler
        ctx.pipeline().remove(this);
    }

    /**
     * 处理失败的 SSL 握手
     *
     * @param ctx Channel 上下文
     * @param cause 失败原因
     */
    private void handleFailedHandshake(ChannelHandlerContext ctx, Throwable cause) {
        String clientAddress = ctx.channel().remoteAddress().toString();
        String reason = classifyHandshakeFailure(cause);

        if (cause != null) {
            if (cause instanceof SslHandshakeTimeoutException) {
                logger.warn("SSL handshake timeout for {}: {}", clientAddress, cause.getMessage());
            } else {
                logger.error("SSL handshake failed for {} [reason={}]: {}", clientAddress, reason, cause.getMessage());
            }
        } else {
            logger.error("SSL handshake failed for {} [reason={}] (unknown cause)", clientAddress, reason);
        }

        // 审计日志记录分类后的握手失败原因
        auditLogger.log(AuditEventType.SSL_HANDSHAKE_FAILED,
                "SSL handshake failed: reason=" + reason + ", client=" + clientAddress);

        // 记录到被拒绝证书追踪器（用于调试端点）
        String causeMessage = cause != null ? cause.getMessage() : "unknown";
        rejectedCertTracker.record(
                clientAddress, null, null, reason + ": " + causeMessage);

        ctx.close();
    }

    /**
     * 异常处理
     *
     * @param ctx Channel 上下文
     * @param cause 异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception in SSL auth handler: {}", cause.getMessage(), cause);
        ctx.close();
    }

    /**
     * 记录 SSL 握手指标
     *
     * @param success 握手是否成功
     * @param cause   失败原因（成功时为 null）
     */
    private void recordSslHandshakeMetrics(boolean success, Throwable cause) {
        long duration = System.currentTimeMillis() - handshakeStartTime;

        if (success) {
            metrics.incrementSslHandshakes();
            metrics.recordSslHandshakeDuration(duration);
        } else {
            String reason = classifyHandshakeFailure(cause);
            metrics.incrementSslHandshakeFailures(reason);
        }
    }

    /**
     * 将 SSL 握手失败原因映射为分类标签
     * <p>Package-private for unit testing.</p>
     *
     * @param cause 握手失败异常
     * @return 分类标签：expired / revoked / untrusted / cipher_mismatch /
     *         protocol_mismatch / no_cert / timeout / other
     */
    static String classifyHandshakeFailure(Throwable cause) {
        if (cause instanceof SslHandshakeTimeoutException) {
            return "timeout";
        }
        Throwable t = cause;
        while (t != null) {
            if (t instanceof CertificateExpiredException) {
                return "expired";
            }
            if (t instanceof CertificateRevokedException) {
                return "revoked";
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("certificate_expired") || lower.contains("certificate expired")
                        || lower.contains("notafter")) {
                    return "expired";
                }
                if (lower.contains("revoked") || lower.contains("certificate_revoked")) {
                    return "revoked";
                }
                if (lower.contains("pkix path") || lower.contains("unable to find valid certification")
                        || lower.contains("unknown ca") || lower.contains("certificate unknown")
                        || lower.contains("key is too weak") || lower.contains("weak signature algorithm")) {
                    return "untrusted";
                }
                if (lower.contains("no cipher") || lower.contains("cipher suites in common")) {
                    return "cipher_mismatch";
                }
                if (lower.contains("no appropriate protocol") || lower.contains("no protocols enabled")
                        || lower.contains("unsupported record version")) {
                    return "protocol_mismatch";
                }
                if (lower.contains("empty client certificate") || lower.contains("peer not authenticated")
                        || lower.contains("certificate required")) {
                    return "no_cert";
                }
            }
            t = t.getCause();
        }
        return "other";
    }
}

