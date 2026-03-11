package com.sslproxy.config;

import java.io.IOException;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.sslproxy.util.CertificateUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.metrics.ProxyMetrics;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;

/**
 * SSL 上下文持有器
 * <p>
 * 支持 SSL 证书热加载的线程安全持有器。
 * 当证书文件变化时，自动重建 SSL 上下文，新连接使用新证书。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class SslContextHolder implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SslContextHolder.class);

    /** 证书过期警告阈值：7 天 */
    private static final long CERT_EXPIRY_WARNING_DAYS = 7;
    /** 证书过期警告阈值（秒） */
    private static final long CERT_EXPIRY_WARNING_SECONDS = CERT_EXPIRY_WARNING_DAYS * 24 * 60 * 60;

    private final ProxyConfig.Ssl sslConfig;
    private final ProxyMetrics metrics;
    private final AuditLogger auditLoggerRef;
    private final com.sslproxy.security.DynamicCertificateManager dynamicCertManager;
    private final com.sslproxy.security.CertificateRevocationChecker revocationChecker;
    private final AtomicReference<SslContext> sslContextRef;
    private final AtomicLong reloadCount = new AtomicLong(0);
    private final AtomicLong lastReloadTime = new AtomicLong(0);

    private WatchService watchService;
    private ExecutorService watcherExecutor;
    private ScheduledExecutorService reloadScheduler;
    private volatile ScheduledFuture<?> pendingReload;
    private volatile boolean watching;

    /**
     * 构造函数
     *
     * @param sslConfig        SSL 配置
     * @param metrics          指标收集器
     * @param auditLogger      审计日志
     * @param dynamicCertManager 动态证书管理器
     * @param revocationChecker  证书吊销检查器（可为 null）
     */
    public SslContextHolder(ProxyConfig.Ssl sslConfig, ProxyMetrics metrics,
                            AuditLogger auditLogger,
                            com.sslproxy.security.DynamicCertificateManager dynamicCertManager,
                            com.sslproxy.security.CertificateRevocationChecker revocationChecker) throws Exception {
        this.sslConfig = sslConfig;
        this.metrics = metrics;
        this.auditLoggerRef = auditLogger;
        this.dynamicCertManager = dynamicCertManager;
        this.revocationChecker = revocationChecker;
        this.sslContextRef = new AtomicReference<>();

        // 初始化 SSL 上下文
        reload();

        logger.info("SslContextHolder initialized");
    }

    /**
     * 获取当前 SSL 上下文
     */
    public SslContext getSslContext() {
        return sslContextRef.get();
    }

    /**
     * 创建新的 SslHandler
     */
    public SslHandler newHandler(ChannelHandlerContext ctx) {
        SslContext sslContext = sslContextRef.get();
        if (sslContext == null) {
            throw new IllegalStateException("SSL context not initialized");
        }
        return sslContext.newHandler(ctx.alloc());
    }

    /**
     * 创建新的 SslHandler
     */
    public SslHandler newHandler(io.netty.buffer.ByteBufAllocator alloc) {
        SslContext sslContext = sslContextRef.get();
        if (sslContext == null) {
            throw new IllegalStateException("SSL context not initialized");
        }
        return sslContext.newHandler(alloc);
    }

    /**
     * 重新加载 SSL 上下文
     */
    public synchronized void reload() throws Exception {
        try {
            SslContext newContext = SSLContextFactory.createServerSslContext(
                    sslConfig, dynamicCertManager, revocationChecker, auditLoggerRef);
            SslContext oldContext = sslContextRef.getAndSet(newContext);

            reloadCount.incrementAndGet();
            lastReloadTime.set(System.currentTimeMillis());

            // 更新证书过期时间指标
            updateCertificateExpiryMetrics();

            // 记录审计日志
            auditLoggerRef.logSslCertReloaded(reloadCount.get());

            logger.info("SSL context reloaded successfully (reload count: {})", reloadCount.get());

            // 释放旧 SslContext 的原生内存（OpenSSL 实现需要显式释放）
            if (oldContext != null) {
                ReferenceCountUtil.release(oldContext);
            }
        } catch (Exception e) {
            logger.error("Failed to reload SSL context: {}", e.getMessage(), e);
            // 如果已有上下文，保持不变
            if (sslContextRef.get() == null) {
                throw e;
            }
        }
    }

    /**
     * 更新证书过期时间指标
     */
    private void updateCertificateExpiryMetrics() {
        try {
            // 更新服务端证书过期时间
            if (sslConfig.keystore != null && sslConfig.keystore.path != null) {
                long serverExpirySeconds = getEarliestExpirySeconds(
                        sslConfig.keystore.path,
                        sslConfig.keystore.password,
                        sslConfig.keystore.type,
                        "server");
                metrics.setServerCertExpirySeconds(serverExpirySeconds);

                if (serverExpirySeconds > 0 && serverExpirySeconds < CERT_EXPIRY_WARNING_SECONDS) {
                    long daysUntilExpiry = serverExpirySeconds / (24 * 60 * 60);
                    logger.warn("Server certificate will expire in {} days!", daysUntilExpiry);
                    auditLoggerRef.logCertificateExpiring("server", daysUntilExpiry);
                }
            }

            // 更新 CA 证书过期时间
            if (sslConfig.truststore != null && sslConfig.truststore.path != null) {
                long caExpirySeconds = getEarliestExpirySeconds(
                        sslConfig.truststore.path,
                        sslConfig.truststore.password,
                        sslConfig.truststore.type,
                        "CA");
                metrics.setCaCertExpirySeconds(caExpirySeconds);

                if (caExpirySeconds > 0 && caExpirySeconds < CERT_EXPIRY_WARNING_SECONDS) {
                    long daysUntilExpiry = caExpirySeconds / (24 * 60 * 60);
                    logger.warn("CA certificate will expire in {} days!", daysUntilExpiry);
                    auditLoggerRef.logCertificateExpiring("CA", daysUntilExpiry);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to update certificate expiry metrics: {}", e.getMessage());
        }
    }

    /**
     * 获取密钥库中最早过期证书的剩余秒数
     *
     * @param keystorePath 密钥库路径
     * @param password     密码
     * @param type         密钥库类型
     * @param certType     证书类型描述（用于日志）
     * @return 距离过期的秒数，如果出错返回 0
     */
    private long getEarliestExpirySeconds(String keystorePath, String password, String type, String certType) {
        try {
            KeyStore keyStore = CertificateManager.loadKeyStore(keystorePath, password, type);
            Date earliestExpiry = null;

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate cert = keyStore.getCertificate(alias);

                if (cert instanceof X509Certificate) {
                    X509Certificate x509Cert = (X509Certificate) cert;
                    Date notAfter = x509Cert.getNotAfter();

                    if (earliestExpiry == null || notAfter.before(earliestExpiry)) {
                        earliestExpiry = notAfter;
                    }

                    logger.debug("{} certificate '{}' expires at: {}", certType, alias, notAfter);
                }
            }

            if (earliestExpiry != null) {
                long expiryMillis = earliestExpiry.getTime() - System.currentTimeMillis();
                return Math.max(0, expiryMillis / 1000);
            }
        } catch (Exception e) {
            logger.error("Failed to get {} certificate expiry: {}", certType, e.getMessage());
        }

        return 0;
    }

    /**
     * 获取当前加载的证书详情列表
     *
     * @return 每个证书的元数据（subject, issuer, serialNumber, notBefore, notAfter, fingerprint）
     */
    public List<Map<String, Object>> getCertificateInfo() {
        List<Map<String, Object>> certs = new ArrayList<>();
        try {
            if (sslConfig.keystore != null && sslConfig.keystore.path != null) {
                KeyStore keyStore = CertificateManager.loadKeyStore(
                        sslConfig.keystore.path, sslConfig.keystore.password, sslConfig.keystore.type);
                Enumeration<String> aliases = keyStore.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    Certificate cert = keyStore.getCertificate(alias);
                    if (cert instanceof X509Certificate x509) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("alias", alias);
                        info.put("subject", x509.getSubjectX500Principal().getName());
                        info.put("issuer", x509.getIssuerX500Principal().getName());
                        info.put("serialNumber", x509.getSerialNumber().toString(16));
                        info.put("notBefore", x509.getNotBefore().toInstant().toString());
                        info.put("notAfter", x509.getNotAfter().toInstant().toString());
                        info.put("fingerprint", CertificateUtil.getCertificateFingerprint(x509));
                        certs.add(info);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get certificate info: {}", e.getMessage());
        }
        return certs;
    }

    /**
     * 获取证书状态
     *
     * @return "UP"（正常）、"EXPIRED"（已过期）、"DOWN"（未初始化）
     */
    public String getCertificateStatus() {
        if (sslContextRef.get() == null) {
            return "DOWN";
        }
        try {
            if (sslConfig.keystore != null && sslConfig.keystore.path != null) {
                long expirySeconds = getEarliestExpirySeconds(
                        sslConfig.keystore.path, sslConfig.keystore.password,
                        sslConfig.keystore.type, "server");
                if (expirySeconds == 0) {
                    return "EXPIRED";
                }
            }
        } catch (Exception e) {
            logger.error("Failed to check certificate status: {}", e.getMessage());
        }
        return "UP";
    }

    /**
     * 启动证书文件监视
     */
    public void startWatching() {
        if (watching) {
            logger.warn("Certificate watching already started");
            return;
        }

        if (sslConfig == null || sslConfig.keystore == null) {
            logger.warn("No keystore configured, skipping certificate watch");
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            watcherExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cert-watcher");
                t.setDaemon(true);
                return t;
            });
            reloadScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cert-reload");
                t.setDaemon(true);
                return t;
            });

            // 注册证书目录监视
            registerCertificateWatch();

            watching = true;
            watcherExecutor.submit(this::watchLoop);

            logger.info("Certificate file watching started");
        } catch (IOException e) {
            logger.error("Failed to start certificate watching: {}", e.getMessage(), e);
        }
    }

    /**
     * 注册证书文件监视
     */
    private void registerCertificateWatch() throws IOException {
        // 确定要监视的目录
        String keystorePath = sslConfig.keystore.path;
        if (keystorePath != null && !keystorePath.isEmpty()) {
            Path certPath = Paths.get(keystorePath).toAbsolutePath();
            Path certDir = certPath.getParent();
            if (certDir != null && Files.exists(certDir)) {
                certDir.register(watchService,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE);
                logger.info("Watching certificate directory: {}", certDir);
            }
        }

        // 如果是 PEM 格式，也监视 certFile 和 keyFile
        if (sslConfig.keystore.certFile != null && !sslConfig.keystore.certFile.isEmpty()) {
            Path certFile = Paths.get(sslConfig.keystore.certFile).toAbsolutePath();
            Path certDir = certFile.getParent();
            if (certDir != null && Files.exists(certDir)) {
                certDir.register(watchService,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE);
                logger.info("Watching PEM certificate directory: {}", certDir);
            }
        }
    }

    /**
     * 监视循环
     */
    private void watchLoop() {
        while (watching) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                boolean shouldReload = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changedPath = pathEvent.context();
                    String fileName = changedPath.toString().toLowerCase();

                    // 检查是否是证书相关文件
                    if (isCertificateFile(fileName)) {
                        logger.info("Certificate file changed: {}", changedPath);
                        shouldReload = true;
                    }
                }

                if (shouldReload) {
                    scheduleCertReload();
                }

                boolean valid = key.reset();
                if (!valid) {
                    logger.warn("WatchKey is no longer valid, stopping certificate watcher");
                    break;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                logger.error("Error in certificate watch loop: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 防抖调度证书重载：200ms 内多次文件变更事件只触发一次重载。
     */
    private void scheduleCertReload() {
        ScheduledExecutorService scheduler = this.reloadScheduler;
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        ScheduledFuture<?> existing = pendingReload;
        if (existing != null) {
            existing.cancel(false);
        }
        pendingReload = scheduler.schedule(() -> {
            try {
                reload();
            } catch (Exception e) {
                logger.error("Failed to reload SSL context after file change: {}", e.getMessage());
            }
        }, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * 判断是否是证书相关文件
     */
    private boolean isCertificateFile(String fileName) {
        return fileName.endsWith(".p12")
                || fileName.endsWith(".pfx")
                || fileName.endsWith(".jks")
                || fileName.endsWith(".keystore")
                || fileName.endsWith(".pem")
                || fileName.endsWith(".crt")
                || fileName.endsWith(".cer")
                || fileName.endsWith(".key");
    }

    /**
     * 停止证书文件监视
     */
    public void stopWatching() {
        watching = false;
        if (reloadScheduler != null) {
            reloadScheduler.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.error("Error closing WatchService: {}", e.getMessage());
            }
        }
        if (watcherExecutor != null) {
            watcherExecutor.shutdown();
            try {
                if (!watcherExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    watcherExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                watcherExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("Certificate file watching stopped");
    }

    /**
     * 获取重载次数
     */
    public long getReloadCount() {
        return reloadCount.get();
    }

    /**
     * 获取最后一次重载时间
     */
    public long getLastReloadTime() {
        return lastReloadTime.get();
    }

    /**
     * 检查是否正在监视
     */
    public boolean isWatching() {
        return watching;
    }

    @Override
    public void close() {
        stopWatching();
    }
}
