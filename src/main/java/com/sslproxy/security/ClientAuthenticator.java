package com.sslproxy.security;

import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.util.CertificateUtil;

/**
 * 客户端证书认证器
 * <p>
 * 自定义的 X509TrustManager 实现，用于验证客户端证书的合法性。
 * 支持证书白名单、黑名单、过期检查等多种验证策略。
 * 使用线程安全的 ConcurrentHashMap 存储白名单和黑名单。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ClientAuthenticator implements X509TrustManager {
    private static final Logger logger = LoggerFactory.getLogger(ClientAuthenticator.class);

    /** EC 密钥最低强度（bit） */
    private static final int MINIMUM_EC_KEY_BITS = 256;

    private final X509TrustManager defaultTrustManager;

    /** 证书指纹白名单（如果非空，只允许白名单中的证书）- 线程安全 */
    private final Set<String> whitelistFingerprints;

    /** 证书指纹黑名单（拒绝黑名单中的证书）- 线程安全 */
    private final Set<String> blacklistFingerprints;

    /** 是否检查证书过期 */
    private final boolean checkExpiry;

    /** 是否验证证书链 */
    private final boolean validateChain;

    /** RSA 密钥最低强度（bit），低于此值的客户端证书将被拒绝 */
    private final int minimumRsaKeyBits;

    /** 证书吊销检查器（可为 null） */
    private final CertificateRevocationChecker revocationChecker;

    /** 审计日志记录器 */
    private final AuditLogger auditLogger;

    /**
     * 构造客户端认证器
     *
     * @param defaultTrustManager 默认的信任管理器（用于基本的证书链验证）
     */
    public ClientAuthenticator(X509TrustManager defaultTrustManager) {
        this(defaultTrustManager, null, null, true, true, 2048, null, null);
    }

    /**
     * 构造客户端认证器（完整配置）
     *
     * @param defaultTrustManager   默认的信任管理器
     * @param whitelistFingerprints 证书指纹白名单
     * @param blacklistFingerprints 证书指纹黑名单
     * @param checkExpiry           是否检查证书过期
     * @param validateChain         是否验证证书链
     */
    public ClientAuthenticator(
            X509TrustManager defaultTrustManager,
            Set<String> whitelistFingerprints,
            Set<String> blacklistFingerprints,
            boolean checkExpiry,
            boolean validateChain) {
        this(defaultTrustManager, whitelistFingerprints, blacklistFingerprints, checkExpiry, validateChain, 2048, null, null);
    }

    /**
     * 构造客户端认证器（完整配置，含密钥强度要求和吊销检查器）
     *
     * @param defaultTrustManager   默认的信任管理器
     * @param whitelistFingerprints 证书指纹白名单
     * @param blacklistFingerprints 证书指纹黑名单
     * @param checkExpiry           是否检查证书过期
     * @param validateChain         是否验证证书链
     * @param minimumRsaKeyBits     RSA 密钥最低强度（bit），建议 2048
     * @param revocationChecker     证书吊销检查器（可为 null）
     * @param auditLogger           审计日志记录器（可为 null，为 null 时创建新实例）
     */
    public ClientAuthenticator(
            X509TrustManager defaultTrustManager,
            Set<String> whitelistFingerprints,
            Set<String> blacklistFingerprints,
            boolean checkExpiry,
            boolean validateChain,
            int minimumRsaKeyBits,
            CertificateRevocationChecker revocationChecker,
            AuditLogger auditLogger) {
        this.defaultTrustManager = defaultTrustManager;
        this.whitelistFingerprints = ConcurrentHashMap.newKeySet();
        this.blacklistFingerprints = ConcurrentHashMap.newKeySet();
        if (whitelistFingerprints != null) {
            this.whitelistFingerprints.addAll(whitelistFingerprints);
        }
        if (blacklistFingerprints != null) {
            this.blacklistFingerprints.addAll(blacklistFingerprints);
        }
        this.checkExpiry = checkExpiry;
        this.validateChain = validateChain;
        this.minimumRsaKeyBits = minimumRsaKeyBits > 0 ? minimumRsaKeyBits : 2048;
        this.revocationChecker = revocationChecker;
        this.auditLogger = auditLogger != null ? auditLogger : new AuditLogger();
    }

    /**
     * 验证客户端证书链
     * <p>
     * 执行以下验证步骤：
     * <ol>
     *   <li>检查证书是否在黑名单中</li>
     *   <li>如果有白名单，检查证书是否在白名单中</li>
     *   <li>检查证书是否过期（如果启用）</li>
     *   <li>检查证书是否已被吊销（OCSP/CRL）</li>
     *   <li>使用默认 TrustManager 验证证书链（如果启用）</li>
     * </ol>
     * </p>
     *
     * @param chain 客户端证书链
     * @param authType 认证类型
     * @throws CertificateException 如果验证失败
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            logger.warn("Client certificate chain is empty");
            throw new CertificateException("Client certificate chain is empty");
        }

        X509Certificate clientCert = chain[0];
        String fingerprint = normalizeFingerprint(CertificateUtil.getCertificateFingerprint(clientCert));
        String summary = CertificateUtil.getCertificateSummary(clientCert);

        logger.debug("Validating client certificate: {}", summary);

        // 1. 检查黑名单
        if (!blacklistFingerprints.isEmpty() && blacklistFingerprints.contains(fingerprint)) {
            logger.warn("Client certificate is blacklisted: {}", summary);
            String cn = CertificateUtil.getCommonName(clientCert);
            auditLogger.logCertRejected(cn, "Certificate is blacklisted");
            throw new CertificateException("Certificate is blacklisted");
        }

        // 2. 检查白名单（如果配置了白名单）
        if (!whitelistFingerprints.isEmpty() && !whitelistFingerprints.contains(fingerprint)) {
            logger.warn("Client certificate not in whitelist: {}", summary);
            String cn = CertificateUtil.getCommonName(clientCert);
            auditLogger.logCertRejected(cn, "Certificate not in whitelist");
            throw new CertificateException("Certificate not in whitelist");
        }

        // 3. 检查证书过期
        if (checkExpiry) {
            if (CertificateUtil.isCertificateExpired(clientCert)) {
                logger.warn("Client certificate has expired: {}", summary);
                String cn = CertificateUtil.getCommonName(clientCert);
                auditLogger.logCertRejected(cn, "Certificate has expired");
                throw new CertificateException("Certificate has expired");
            }

            // 检查是否即将过期（7天内）
            if (CertificateUtil.isCertificateExpiringSoon(clientCert, 7)) {
                long daysRemaining = CertificateUtil.getRemainingValidityDays(clientCert);
                logger.warn("Client certificate expires soon ({} days): {}", daysRemaining, summary);
            }
        }

        // 3.5 检查密钥强度（RSA < minimumRsaKeyBits / EC < 256 bit / SHA-1/MD5 签名被拒绝）
        checkKeyStrength(clientCert);

        // 4. 检查证书吊销状态（OCSP/CRL）
        if (revocationChecker != null) {
            X509Certificate issuerCert = chain.length > 1 ? chain[1] : null;
            revocationChecker.checkRevocation(clientCert, issuerCert, fingerprint);
            logger.debug("Certificate revocation check passed: {}", summary);
        }

        // 5. 验证证书链
        if (validateChain) {
            if (defaultTrustManager == null) {
                throw new CertificateException("No trust manager configured for certificate chain validation");
            }
            try {
                defaultTrustManager.checkClientTrusted(chain, authType);
                logger.debug("Client certificate chain validated successfully: {}", summary);
            } catch (CertificateException e) {
                logger.error("Client certificate chain validation failed: {}, error: {}",
                        summary, e.getMessage());
                throw e;
            }
        }

        logger.info("Client certificate authenticated successfully: {}", summary);
    }

    /**
     * 检查客户端证书密钥强度和签名算法
     * <p>
     * 拒绝条件：
     * - RSA 密钥长度 < minimumRsaKeyBits（默认 2048）
     * - EC 密钥长度 < 256 bit
     * - 签名算法为 SHA1withRSA、SHA1withECDSA 或 MD5withRSA
     * </p>
     */
    private void checkKeyStrength(X509Certificate cert) throws CertificateException {
        PublicKey pubKey = cert.getPublicKey();
        String algorithm = pubKey.getAlgorithm();

        if ("RSA".equalsIgnoreCase(algorithm)) {
            int bitLength = ((RSAKey) pubKey).getModulus().bitLength();
            if (bitLength < minimumRsaKeyBits) {
                String cn = CertificateUtil.getCommonName(cert);
                auditLogger.logCertRejected(cn,
                        "RSA key too weak: " + bitLength + " bits (minimum: " + minimumRsaKeyBits + ")");
                throw new CertificateException("Client certificate RSA key is too weak: "
                        + bitLength + " bits (minimum: " + minimumRsaKeyBits + ")");
            }
        } else if ("EC".equalsIgnoreCase(algorithm) && pubKey instanceof ECKey) {
            int fieldSize = ((ECKey) pubKey).getParams().getCurve().getField().getFieldSize();
            if (fieldSize < MINIMUM_EC_KEY_BITS) {
                String cn = CertificateUtil.getCommonName(cert);
                auditLogger.logCertRejected(cn,
                        "EC key too weak: " + fieldSize + " bits (minimum: " + MINIMUM_EC_KEY_BITS + ")");
                throw new CertificateException("Client certificate EC key is too weak: "
                        + fieldSize + " bits (minimum: " + MINIMUM_EC_KEY_BITS + ")");
            }
        }

        // 拒绝弱签名算法：SHA-1 和 MD5
        String sigAlgUpper = cert.getSigAlgName().toUpperCase();
        if (sigAlgUpper.contains("MD5") || sigAlgUpper.startsWith("SHA1WITH") || "SHA1".equals(sigAlgUpper)) {
            String cn = CertificateUtil.getCommonName(cert);
            auditLogger.logCertRejected(cn,
                    "Weak signature algorithm: " + cert.getSigAlgName());
            throw new CertificateException("Client certificate uses weak signature algorithm: "
                    + cert.getSigAlgName());
        }
    }

    /**
     * 验证服务器证书链
     * <p>
     * 当代理作为客户端连接后端服务器时使用。
     * 委托给默认的 TrustManager 进行验证。
     * </p>
     * 
     * @param chain 服务器证书链
     * @param authType 认证类型
     * @throws CertificateException 如果验证失败
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (defaultTrustManager != null) {
            defaultTrustManager.checkServerTrusted(chain, authType);
        } else {
            throw new CertificateException("No trust manager configured for server certificate validation");
        }
    }

    /**
     * 获取接受的颁发者证书
     * 
     * @return 接受的颁发者证书数组
     */
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        if (defaultTrustManager != null) {
            return defaultTrustManager.getAcceptedIssuers();
        }
        return new X509Certificate[0];
    }

    /**
     * 添加证书指纹到白名单
     * 
     * @param fingerprint 证书 SHA-256 指纹
     */
    public void addToWhitelist(String fingerprint) {
        String normalized = normalizeFingerprint(fingerprint);
        if (normalized != null) {
            whitelistFingerprints.add(normalized);
            logger.info("Added certificate to whitelist: {}", normalized);
        }
    }

    /**
     * 从白名单移除证书指纹
     * 
     * @param fingerprint 证书 SHA-256 指纹
     */
    public void removeFromWhitelist(String fingerprint) {
        String normalized = normalizeFingerprint(fingerprint);
        if (normalized != null && whitelistFingerprints.remove(normalized)) {
            logger.info("Removed certificate from whitelist: {}", normalized);
        }
    }

    /**
     * 添加证书指纹到黑名单
     * 
     * @param fingerprint 证书 SHA-256 指纹
     */
    public void addToBlacklist(String fingerprint) {
        String normalized = normalizeFingerprint(fingerprint);
        if (normalized != null) {
            blacklistFingerprints.add(normalized);
            logger.warn("Added certificate to blacklist: {}", normalized);
        }
    }

    /**
     * 从黑名单移除证书指纹
     * 
     * @param fingerprint 证书 SHA-256 指纹
     */
    public void removeFromBlacklist(String fingerprint) {
        String normalized = normalizeFingerprint(fingerprint);
        if (normalized != null && blacklistFingerprints.remove(normalized)) {
            logger.info("Removed certificate from blacklist: {}", normalized);
        }
    }

    private static String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        String trimmed = fingerprint.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toUpperCase();
    }

    /**
     * 获取白名单大小
     * 
     * @return 白名单中的证书数量
     */
    public int getWhitelistSize() {
        return whitelistFingerprints.size();
    }

    /**
     * 获取黑名单大小
     * 
     * @return 黑名单中的证书数量
     */
    public int getBlacklistSize() {
        return blacklistFingerprints.size();
    }
}

