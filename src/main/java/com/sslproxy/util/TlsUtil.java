package com.sslproxy.util;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TLS 工具类
 */
public final class TlsUtil {
    private static final Logger logger = LoggerFactory.getLogger(TlsUtil.class);

    private TlsUtil() {
    }

    /**
     * 为客户端侧 TLS 启用 HTTPS 主机名校验（Hostname Verification）。
     * <p>
     * 等价于 JDK HTTP 客户端的 endpoint identification，能防止证书链合法但域名不匹配的 MITM。
     * </p>
     */
    public static void enableHostnameVerification(SSLEngine engine) {
        if (engine == null) {
            return;
        }
        try {
            SSLParameters params = engine.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            engine.setSSLParameters(params);
        } catch (Exception e) {
            logger.warn("Failed to enable hostname verification: {}", e.getMessage());
        }
    }
}

