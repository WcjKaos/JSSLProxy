package com.sslproxy.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.security.CertificateRevocationChecker;
import com.sslproxy.security.ClientAuthenticator;
import com.sslproxy.security.DynamicCertificateManager;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;

/**
 * SSL/TLS 上下文工厂
 * <p>
 * 负责创建 Netty 的 SslContext 对象，用于服务端和客户端的 SSL/TLS 连接。
 * 支持两种证书格式：
 * <ul>
 *   <li>JKS/PKCS12 密钥库格式 - 证书和私钥打包在同一个文件中</li>
 *   <li>PEM 格式 - 证书文件(.crt)和私钥文件(.key)分离</li>
 * </ul>
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class SSLContextFactory {
    private static final Logger logger = LoggerFactory.getLogger(SSLContextFactory.class);
    
    /**
     * 服务端的客户端认证器实例（用于运行时动态管理白名单/黑名单）
     */
    private static volatile ClientAuthenticator serverAuthenticator;

    /**
     * 创建服务端 SSL 上下文
     * <p>
     * 用于代理服务器接受客户端连接时的 SSL/TLS 握手。
     * 支持两种证书加载方式：
     * <ol>
     *   <li>PEM 格式：直接从 certFile 和 keyFile 加载</li>
     *   <li>JKS/PKCS12 格式：通过 CertificateManager 从密钥库加载</li>
     * </ol>
     * </p>
     * 
     * @param ssl                SSL 配置对象
     * @param dynamicCertManager 动态证书管理器（可为 null）
     * @param revocationChecker  证书吊销检查器（可为 null）
     * @return Netty SslContext 对象
     * @throws Exception 如果证书加载或 SSL 上下文创建失败
     */
    public static SslContext createServerSslContext(ProxyConfig.Ssl ssl,
                                                     DynamicCertificateManager dynamicCertManager,
                                                     CertificateRevocationChecker revocationChecker,
                                                     AuditLogger auditLogger) throws Exception {
        if (ssl == null || ssl.keystore == null) {
            throw new IllegalArgumentException("ssl.keystore required");
        }

        SslContextBuilder builder;
        
        // 判断使用哪种证书格式
        if (isPemFormat(ssl.keystore)) {
            // 方式 1: PEM 格式 - 证书和私钥在不同文件中
            File certFile = new File(ssl.keystore.certFile);
            File keyFile = new File(ssl.keystore.keyFile);

            builder = SslContextBuilder.forServer(certFile, keyFile, ssl.keystore.password);

            // 配置信任库（用于验证客户端证书）
            if (ssl.truststore != null && ssl.truststore.path != null) {
                // 加载 PEM 格式的 trust manager，并包装在 ClientAuthenticator 中
                // 以确保白名单/黑名单/吊销检查也对 PEM 格式生效
                File trustFile = new File(ssl.truststore.path);
                TrustManagerFactory pemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                KeyStore pemTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                pemTrustStore.load(null, null);
                try (InputStream trustStream = new FileInputStream(trustFile)) {
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    int certIndex = 0;
                    for (java.security.cert.Certificate cert : cf.generateCertificates(trustStream)) {
                        pemTrustStore.setCertificateEntry("trust-" + certIndex++, cert);
                    }
                }
                pemTmf.init(pemTrustStore);
                X509TrustManager pemTrustManager = (X509TrustManager) pemTmf.getTrustManagers()[0];

                // 从配置读取白名单/黑名单
                Set<String> whitelist = null;
                Set<String> blacklist = null;
                if (ssl.whitelistFingerprints != null && !ssl.whitelistFingerprints.isEmpty()) {
                    whitelist = new HashSet<>(ssl.whitelistFingerprints);
                }
                if (ssl.blacklistFingerprints != null && !ssl.blacklistFingerprints.isEmpty()) {
                    blacklist = new HashSet<>(ssl.blacklistFingerprints);
                }

                serverAuthenticator = new ClientAuthenticator(
                    pemTrustManager, whitelist, blacklist, true, true, 2048, revocationChecker, auditLogger
                );
                if (dynamicCertManager != null) {
                    dynamicCertManager.setAuthenticator(serverAuthenticator);
                }
                builder.trustManager(serverAuthenticator);
            }
        } else {
            // 方式 2: JKS/PKCS12 格式 - 证书和私钥在同一个密钥库文件中
            CertificateManager cm = new CertificateManager(ssl).load();
            builder = SslContextBuilder.forServer(cm.getKeyManagerFactory());
            
            // 使用 ClientAuthenticator 包装 TrustManager，提供额外的验证功能
            TrustManagerFactory tmf = cm.getTrustManagerFactory();
            if (tmf != null && tmf.getTrustManagers() != null && tmf.getTrustManagers().length > 0) {
                X509TrustManager defaultTrustManager = (X509TrustManager) tmf.getTrustManagers()[0];
                
                // 从配置读取白名单/黑名单
                Set<String> whitelist = null;
                Set<String> blacklist = null;
                
                if (ssl.whitelistFingerprints != null && !ssl.whitelistFingerprints.isEmpty()) {
                    whitelist = new HashSet<>(ssl.whitelistFingerprints);
                }
                
                if (ssl.blacklistFingerprints != null && !ssl.blacklistFingerprints.isEmpty()) {
                    blacklist = new HashSet<>(ssl.blacklistFingerprints);
                }
                
                // 创建带白名单/黑名单的认证器
                serverAuthenticator = new ClientAuthenticator(
                    defaultTrustManager,
                    whitelist,
                    blacklist,
                    true,  // 检查证书过期
                    true,  // 验证证书链
                    2048,
                    revocationChecker,
                    auditLogger
                );

                // Bind authenticator to dynamic cert manager so /admin whitelist/blacklist takes effect immediately.
                if (dynamicCertManager != null) {
                    dynamicCertManager.setAuthenticator(serverAuthenticator);
                }
                
                builder.trustManager(serverAuthenticator);
            } else {
                builder.trustManager(cm.getTrustManagerFactory());
            }
        }
        
        configureClientAuth(builder, ssl.clientAuth);
        applyProtocolsAndCiphers(builder, ssl.protocols, ssl.ciphers);
        return builder.build();
    }

    /**
     * 创建客户端 SSL 上下文
     * <p>
     * 用于代理服务器连接后端目标服务器时的 SSL/TLS 握手。
     * <ul>
     *   <li>KeyManager：如果后端要求客户端证书（mTLS），从 keystore 加载</li>
     *   <li>TrustManager：
     *     <ul>
     *       <li>如果没有配置 truststore → 使用系统默认（信任公网 CA，连接公网服务器）</li>
     *       <li>如果配置了 truststore → 使用自定义 truststore（连接需要自定义 CA 的后端）</li>
     *     </ul>
     *   </li>
     * </ul>
     * </p>
     * 
     * @param ssl SSL 配置对象，可以为 null（表示不使用 SSL）
     * @return Netty SslContext 对象
     * @throws Exception 如果证书加载或 SSL 上下文创建失败
     */
    public static SslContext createClientSslContext(ProxyConfig.Ssl ssl) throws Exception {
        SslContextBuilder builder = SslContextBuilder.forClient();
        
        if (ssl == null) {
            // 没有 SSL 配置，使用默认（信任系统 CA）
            return builder.build();
        }
        
        // 1. 配置 KeyManager（如果后端要求客户端证书）
        if (ssl.keystore != null) {
            if (isPemFormat(ssl.keystore)) {
                // PEM 格式
                File certFile = new File(ssl.keystore.certFile);
                File keyFile = new File(ssl.keystore.keyFile);
                builder.keyManager(certFile, keyFile, ssl.keystore.password);
                logger.info("Client SSL context: configured PEM key manager (for backend mTLS)");
            } else {
                // JKS/PKCS12 格式
                CertificateManager cm = new CertificateManager(ssl).load();
                builder.keyManager(cm.getKeyManagerFactory());
                logger.info("Client SSL context: configured KeyStore key manager (for backend mTLS)");
            }
        }
        
        // 2. 配置 TrustManager（验证后端服务器证书）
        // 通过 ssl.backendTrustStrategy 控制信任策略：
        //   SYSTEM  — 使用系统默认 CA（适用于公网 HTTPS 后端）
        //   CUSTOM  — 仅使用用户配置的 truststore（适用于自签名/内部 CA 后端）
        //   BOTH    — 自定义优先，系统 CA 兜底

        String trustStrategy = ssl.backendTrustStrategy != null ? ssl.backendTrustStrategy.toUpperCase() : "SYSTEM";
        if ("SYSTEM".equals(trustStrategy)) {
            logger.info("Client SSL context: using system default CA");
        } else if ("CUSTOM".equals(trustStrategy)) {
            X509TrustManager custom = createCustomTrustManager(ssl);
            if (custom == null) {
                throw new IllegalArgumentException("backendTrustStrategy=CUSTOM requires ssl.truststore");
            }
            builder.trustManager(custom);
            logger.info("Client SSL context: using custom truststore");
        } else if ("BOTH".equals(trustStrategy)) {
            X509TrustManager custom = createCustomTrustManager(ssl);
            X509TrustManager system = createSystemTrustManager();
            if (custom == null) {
                throw new IllegalArgumentException("backendTrustStrategy=BOTH requires ssl.truststore");
            }
            builder.trustManager(new CompositeTrustManager(custom, system));
            logger.info("Client SSL context: using custom truststore with system fallback");
        } else {
            throw new IllegalArgumentException("Unsupported backendTrustStrategy: " + trustStrategy);
        }
        
        applyProtocolsAndCiphers(builder, ssl.protocols, ssl.ciphers);
        return builder.build();
    }

    /**
     * 配置客户端证书认证模式
     * 
     * @param builder SslContextBuilder 对象
     * @param mode 认证模式字符串 (NONE/OPTIONAL/REQUIRED)
     */
    private static void configureClientAuth(SslContextBuilder builder, String mode) {
        ClientAuth clientAuth = ClientAuth.REQUIRE;
        if (mode != null) {
            clientAuth = switch (mode.toUpperCase()) {
                case "NONE" -> ClientAuth.NONE;
                case "OPTIONAL" -> ClientAuth.OPTIONAL;
                default -> ClientAuth.REQUIRE;
            };
        }
        builder.clientAuth(clientAuth);
    }

    /**
     * 应用 TLS 协议版本和加密套件配置
     * 
     * @param builder SslContextBuilder 对象
     * @param protocols TLS 协议版本列表 (例如: TLSv1.2, TLSv1.3)
     * @param ciphers 加密套件列表 (例如: TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384)
     */
    private static void applyProtocolsAndCiphers(SslContextBuilder builder, List<String> protocols, List<String> ciphers) {
        if (protocols != null && !protocols.isEmpty()) {
            builder.protocols(protocols.toArray(String[]::new));
        }
        if (ciphers != null && !ciphers.isEmpty()) {
            builder.ciphers(ciphers, SupportedCipherSuiteFilter.INSTANCE);
        }
    }

    private static X509TrustManager createCustomTrustManager(ProxyConfig.Ssl ssl) throws Exception {
        if (ssl.truststore == null || ssl.truststore.path == null || ssl.truststore.path.isEmpty()) {
            return null;
        }
        KeyStore trustStore = CertificateManager.loadKeyStore(
                ssl.truststore.path,
                ssl.truststore.password,
                ssl.truststore.type
        );
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return (X509TrustManager) tmf.getTrustManagers()[0];
    }

    private static X509TrustManager createSystemTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return (X509TrustManager) tmf.getTrustManagers()[0];
    }

    private static class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager primary;
        private final X509TrustManager fallback;

        CompositeTrustManager(X509TrustManager primary, X509TrustManager fallback) {
            this.primary = primary;
            this.fallback = fallback;
        }

        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            try {
                primary.checkClientTrusted(chain, authType);
            } catch (java.security.cert.CertificateException e) {
                fallback.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            try {
                primary.checkServerTrusted(chain, authType);
            } catch (java.security.cert.CertificateException e) {
                fallback.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            java.security.cert.X509Certificate[] primaryIssuers = primary.getAcceptedIssuers();
            java.security.cert.X509Certificate[] fallbackIssuers = fallback.getAcceptedIssuers();
            java.security.cert.X509Certificate[] combined =
                    new java.security.cert.X509Certificate[primaryIssuers.length + fallbackIssuers.length];
            System.arraycopy(primaryIssuers, 0, combined, 0, primaryIssuers.length);
            System.arraycopy(fallbackIssuers, 0, combined, primaryIssuers.length, fallbackIssuers.length);
            return combined;
        }
    }

    /**
     * 判断是否使用 PEM 格式的证书
     * <p>
     * 如果同时配置了 certFile 和 keyFile，则认为是 PEM 格式。
     * </p>
     * 
     * @param keystoreConfig 密钥库配置对象
     * @return true 表示 PEM 格式，false 表示 JKS/PKCS12 格式
     */
    private static boolean isPemFormat(ProxyConfig.KeystoreConfig keystoreConfig) {
        return keystoreConfig.certFile != null && !keystoreConfig.certFile.isEmpty()
            && keystoreConfig.keyFile != null && !keystoreConfig.keyFile.isEmpty();
    }

    /**
     * 获取服务端的客户端认证器
     * <p>
     * 可用于运行时动态管理白名单/黑名单。
     * 如果 SSL 上下文尚未创建或使用 PEM 格式证书，可能返回 null。
     * </p>
     * 
     * @return ClientAuthenticator 实例，如果未创建则返回 null
     */
    public static ClientAuthenticator getServerAuthenticator() {
        return serverAuthenticator;
    }

}
