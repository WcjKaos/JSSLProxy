package com.sslproxy.management.handler;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.SslContextHolder;
import com.sslproxy.metrics.ConnectionTracker;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.security.DynamicCertificateManager;
import com.sslproxy.security.RejectedCertTracker;

/**
 * AdminApiHandler 的依赖打包对象。
 * <p>
 * 避免 AdminApiHandler 构造器参数过多（8 个）。
 * 仅在 ManagementInitializer 中构建。
 * </p>
 */
public record AdminServices(
        ConnectionTracker connectionTracker,
        DynamicCertificateManager dynamicCertManager,
        RejectedCertTracker rejectedCertTracker,
        SslContextHolder sslContextHolder,
        AuditLogger auditLogger,
        ProxyMetrics metrics
) {
}
