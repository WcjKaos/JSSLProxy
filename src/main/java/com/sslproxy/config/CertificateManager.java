package com.sslproxy.config;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 证书管理器
 * <p>
 * 负责加载和管理 SSL/TLS 证书，包括密钥库（KeyStore）和信任库（TrustStore）。
 * 支持 JKS、PKCS12 等多种密钥库格式。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class CertificateManager {
    private static final Logger logger = LoggerFactory.getLogger(CertificateManager.class);
    
    private final ProxyConfig.Ssl sslConfig;

    /** 密钥库，包含服务器的私钥和证书 */
    private KeyStore keyStore;
    
    /** 信任库，包含受信任的 CA 证书 */
    private KeyStore trustStore;
    
    /** 密钥管理器工厂，用于提供服务器证书 */
    private KeyManagerFactory keyManagerFactory;
    
    /** 信任管理器工厂，用于验证客户端证书 */
    private TrustManagerFactory trustManagerFactory;

    /**
     * 构造证书管理器
     * 
     * @param sslConfig SSL 配置对象
     */
    public CertificateManager(ProxyConfig.Ssl sslConfig) {
        this.sslConfig = sslConfig;
    }

    /**
     * 加载证书和密钥材料
     * <p>
     * 执行以下操作：
     * <ol>
     *   <li>从配置的路径加载密钥库和信任库</li>
     *   <li>初始化 KeyManagerFactory（用于提供服务器证书）</li>
     *   <li>初始化 TrustManagerFactory（用于验证客户端证书）</li>
     * </ol>
     * </p>
     * 
     * @return 返回当前实例，支持链式调用
     * @throws Exception 如果证书加载或初始化失败
     */
    public CertificateManager load() throws Exception {
        if (sslConfig == null) {
            throw new IllegalArgumentException("ssl config is null");
        }
        if (sslConfig.keystore == null || sslConfig.truststore == null) {
            throw new IllegalArgumentException("keystore/truststore config is required");
        }

        // 加载密钥库（包含服务器的私钥和证书）
        this.keyStore = loadKeyStore(
                sslConfig.keystore.path,
                sslConfig.keystore.password,
                sslConfig.keystore.type
        );
        
        // 加载信任库（包含受信任的客户端 CA 证书）
        this.trustStore = loadKeyStore(
                sslConfig.truststore.path,
                sslConfig.truststore.password,
                sslConfig.truststore.type
        );

        // 初始化密钥管理器工厂（用于 SSL 握手时提供服务器证书）
        String kmfAlgorithm = KeyManagerFactory.getDefaultAlgorithm();
        this.keyManagerFactory = KeyManagerFactory.getInstance(kmfAlgorithm);
        this.keyManagerFactory.init(keyStore, sslConfig.keystore.password != null ? sslConfig.keystore.password.toCharArray() : new char[0]);

        // 初始化信任管理器工厂（用于验证客户端证书）
        String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
        this.trustManagerFactory = TrustManagerFactory.getInstance(tmfAlgorithm);
        this.trustManagerFactory.init(trustStore);

        return this;
    }

    /**
     * 加载密钥库文件
     * <p>
     * 支持两种加载方式：
     * <ol>
     *   <li>优先从 classpath（资源目录）加载，适用于开发环境和打包后的 JAR</li>
     *   <li>如果 classpath 找不到，则从文件系统加载绝对/相对路径</li>
     * </ol>
     * </p>
     * <p>
     * 支持的密钥库类型：
     * <ul>
     *   <li>PKCS12 (.p12, .pfx) - 推荐格式</li>
     *   <li>JKS (.jks) - Java 传统格式</li>
     *   <li>其他 Java KeyStore 支持的格式</li>
     * </ul>
     * </p>
     * 
     * @param path 密钥库文件路径
     * @param password 密钥库密码
     * @param type 密钥库类型 (JKS/PKCS12 等)，如果为空则默认使用 PKCS12
     * @return 加载的 KeyStore 对象
     * @throws Exception 如果文件不存在或加载失败
     */
    public static KeyStore loadKeyStore(String path, String password, String type) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("keystore path is empty");
        }
        
        String useType = (type == null || type.isEmpty()) ? "PKCS12" : type;
        KeyStore ks = KeyStore.getInstance(useType);
        char[] passwordChars = password != null ? password.toCharArray() : new char[0];
        
        // 1. 优先尝试从 classpath 加载（适用于开发环境和打包后的 JAR）
        try (InputStream is = CertificateManager.class.getClassLoader().getResourceAsStream(path)) {
            if (is != null) {
                logger.info("Loading keystore from classpath: {}", path);
                ks.load(is, passwordChars);
                return ks;
            }
        }
        
        // 2. 从文件系统加载
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("keystore path does not exist in classpath or filesystem: " + path);
        }
        
        logger.info("Loading keystore from filesystem: {}", filePath.toAbsolutePath());
        try (FileInputStream fis = new FileInputStream(path)) {
            ks.load(fis, passwordChars);
        }
        return ks;
    }

    /**
     * 获取密钥管理器工厂
     * <p>
     * 用于在 SSL/TLS 握手时提供服务器证书和私钥
     * </p>
     * 
     * @return KeyManagerFactory 实例
     */
    public KeyManagerFactory getKeyManagerFactory() {
        return keyManagerFactory;
    }

    /**
     * 获取信任管理器工厂
     * <p>
     * 用于验证客户端证书的合法性
     * </p>
     * 
     * @return TrustManagerFactory 实例
     */
    public TrustManagerFactory getTrustManagerFactory() {
        return trustManagerFactory;
    }

    /**
     * 获取密钥库对象
     * 
     * @return KeyStore 实例
     */
    public KeyStore getKeyStore() {
        return keyStore;
    }

    /**
     * 获取信任库对象
     * 
     * @return KeyStore 实例
     */
    public KeyStore getTrustStore() {
        return trustStore;
    }
}
