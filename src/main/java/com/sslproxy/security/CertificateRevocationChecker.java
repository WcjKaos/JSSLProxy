package com.sslproxy.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertPathValidatorException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.bouncycastle.asn1.ASN1IA5String;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.config.ProxyConfig.RevocationConfig;

/**
 * 证书吊销检查器
 * <p>
 * 提供 OCSP（在线证书状态协议）和 CRL（证书吊销列表）检查功能。
 * 支持结果缓存以提高性能。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class CertificateRevocationChecker {
    private static final Logger logger = LoggerFactory.getLogger(CertificateRevocationChecker.class);

    // 注册 Bouncy Castle 安全提供器
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
            logger.debug("Bouncy Castle security provider registered");
        }
    }

    private final RevocationConfig config;

    /** OCSP/CRL 响应最大大小：1MB */
    private static final int MAX_RESPONSE_SIZE = 1024 * 1024;

    /** 吊销缓存最大容量 */
    private static final int MAX_CACHE_SIZE = 10000;

    /** 吊销状态缓存：指纹 -> (是否已吊销) */
    private final Cache<String, CacheEntry> revocationCache;

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        final boolean revoked;
        final String reason;

        CacheEntry(boolean revoked, String reason) {
            this.revoked = revoked;
            this.reason = reason;
        }
    }

    /**
     * 构造函数
     *
     * @param config 吊销检查配置
     */
    public CertificateRevocationChecker(RevocationConfig config) {
        this.config = config;
        this.revocationCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_SIZE)
                .expireAfterWrite(config.cacheTtlSeconds, TimeUnit.SECONDS)
                .build();
        logger.info("CertificateRevocationChecker initialized: OCSP={}, CRL={}, softFail={}",
                config.ocspEnabled, config.crlEnabled, config.softFail);
    }

    /**
     * 检查证书是否已被吊销
     *
     * @param certificate     要检查的证书
     * @param issuerCert      颁发者证书（用于 OCSP 验证）
     * @param fingerprint     证书指纹（用于缓存）
     * @throws CertificateException 如果证书已被吊销
     */
    public void checkRevocation(X509Certificate certificate, X509Certificate issuerCert, String fingerprint)
            throws CertificateException {
        if (config == null || !config.enabled) {
            return;
        }

        // 检查缓存
        if (config.cacheEnabled && fingerprint != null) {
            CacheEntry cached = revocationCache.getIfPresent(fingerprint);
            if (cached != null) {
                if (cached.revoked) {
                    throw new CertificateException("Certificate revoked (cached): " + cached.reason);
                }
                logger.debug("Certificate revocation status from cache: not revoked");
                return;
            }
        }

        boolean revoked = false;
        String reason = null;

        try {
            // 优先尝试 OCSP 检查
            if (config.ocspEnabled) {
                try {
                    OcspResult ocspResult = checkOcsp(certificate, issuerCert);
                    if (ocspResult.status == RevocationStatus.REVOKED) {
                        revoked = true;
                        reason = "OCSP: " + ocspResult.reason;
                    } else if (ocspResult.status == RevocationStatus.GOOD) {
                        // OCSP 确认证书有效，缓存结果并返回
                        cacheResult(fingerprint, false, null);
                        return;
                    }
                    // OCSP 状态为 UNKNOWN，继续尝试 CRL
                } catch (Exception e) {
                    logger.warn("OCSP check failed, will try CRL: {}", e.getMessage());
                    if (!config.crlEnabled && !config.softFail) {
                        throw new CertificateException("OCSP check failed and CRL is disabled: " + e.getMessage());
                    }
                }
            }

            // 如果 OCSP 未能确定状态，尝试 CRL 检查
            if (!revoked && config.crlEnabled) {
                try {
                    CrlResult crlResult = checkCrl(certificate, issuerCert);
                    if (crlResult.status == RevocationStatus.REVOKED) {
                        revoked = true;
                        reason = "CRL: " + crlResult.reason;
                    }
                } catch (Exception e) {
                    logger.warn("CRL check failed: {}", e.getMessage());
                    if (!config.softFail) {
                        throw new CertificateException("CRL check failed: " + e.getMessage());
                    }
                }
            }
        } catch (CertificateException e) {
            throw e;
        } catch (Exception e) {
            if (config.softFail) {
                logger.warn("Revocation check failed, soft-fail enabled, allowing certificate: {}", e.getMessage());
                return;
            }
            throw new CertificateException("Revocation check failed: " + e.getMessage());
        }

        // 缓存结果
        cacheResult(fingerprint, revoked, reason);

        if (revoked) {
            logger.warn("Certificate is revoked: {}", reason);
            throw new CertificateException("Certificate revoked: " + reason);
        }
    }

    /**
     * 执行 OCSP 检查
     */
    private OcspResult checkOcsp(X509Certificate certificate, X509Certificate issuerCert) throws Exception {
        if (issuerCert == null) {
            logger.warn("OCSP check skipped: issuer certificate missing");
            return new OcspResult(RevocationStatus.UNKNOWN, "Missing issuer certificate");
        }
        // 获取 OCSP 响应器 URL
        String ocspUrl = config.ocspResponderUrl;
        if (ocspUrl == null || ocspUrl.isEmpty()) {
            ocspUrl = extractOcspUrl(certificate);
        }

        if (ocspUrl == null || ocspUrl.isEmpty()) {
            logger.debug("No OCSP responder URL found in certificate or config");
            return new OcspResult(RevocationStatus.UNKNOWN, "No OCSP URL");
        }

        // 验证 URL 以防止 SSRF 攻击
        if (!isValidRevocationUrl(ocspUrl)) {
            logger.warn("Invalid OCSP URL rejected: {}", ocspUrl);
            return new OcspResult(RevocationStatus.UNKNOWN, "Invalid OCSP URL");
        }

        logger.debug("Checking OCSP at: {}", ocspUrl);
        return performHttpOcspCheck(ocspUrl, certificate, issuerCert);
    }

    /**
     * 执行 HTTP OCSP 检查（使用 Bouncy Castle 完整实现）
     * <p>
     * 按照 RFC 6960 构建 OCSP 请求并解析响应。
     * </p>
     */
    private OcspResult performHttpOcspCheck(String ocspUrl, X509Certificate cert, X509Certificate issuer) {
        HttpURLConnection conn = null;
        try {
            // 1. 构建 OCSP 请求
            DigestCalculatorProvider digCalcProv = new JcaDigestCalculatorProviderBuilder()
                    .setProvider("BC")
                    .build();

            X509CertificateHolder issuerHolder = new X509CertificateHolder(issuer.getEncoded());
            CertificateID certId = new CertificateID(
                    digCalcProv.get(CertificateID.HASH_SHA1),
                    issuerHolder,
                    cert.getSerialNumber());

            OCSPReqBuilder reqBuilder = new OCSPReqBuilder();
            reqBuilder.addRequest(certId);

            // 添加 nonce 扩展以防止重放攻击
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            Extension nonceExtension = new Extension(
                    OCSPObjectIdentifiers.id_pkix_ocsp_nonce,
                    false,
                    new DEROctetString(nonce));
            reqBuilder.setRequestExtensions(new Extensions(nonceExtension));

            OCSPReq ocspReq = reqBuilder.build();
            byte[] ocspReqData = ocspReq.getEncoded();

            // 2. 发送 POST 请求到 OCSP 响应器
            URL url = URI.create(ocspUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(config.ocspTimeout);
            conn.setReadTimeout(config.ocspTimeout);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/ocsp-request");
            conn.setRequestProperty("Accept", "application/ocsp-response");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(ocspReqData);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                logger.warn("OCSP responder returned HTTP {}", responseCode);
                return new OcspResult(RevocationStatus.UNKNOWN, "OCSP HTTP error: " + responseCode);
            }

            // 3. 解析 OCSP 响应
            try (InputStream is = conn.getInputStream()) {
                byte[] respData = readLimited(is, MAX_RESPONSE_SIZE);
                OCSPResp ocspResp = new OCSPResp(respData);

                // 检查响应状态
                int status = ocspResp.getStatus();
                if (status != OCSPResp.SUCCESSFUL) {
                    String statusMsg = getOcspStatusMessage(status);
                    logger.warn("OCSP response status: {}", statusMsg);
                    return new OcspResult(RevocationStatus.UNKNOWN, "OCSP status: " + statusMsg);
                }

                // 获取基本响应
                Object respObject = ocspResp.getResponseObject();
                if (!(respObject instanceof BasicOCSPResp)) {
                    return new OcspResult(RevocationStatus.UNKNOWN, "Invalid OCSP response type");
                }

                BasicOCSPResp basicResp = (BasicOCSPResp) respObject;

                // 验证 OCSP 响应签名
                try {
                    ContentVerifierProvider verifier = new JcaContentVerifierProviderBuilder()
                        .setProvider("BC")
                        .build(issuerHolder);

                    if (!basicResp.isSignatureValid(verifier)) {
                        logger.warn("OCSP response signature verification failed");
                        return new OcspResult(RevocationStatus.UNKNOWN, "Invalid OCSP signature");
                    }
                    logger.debug("OCSP response signature verified");

                } catch (Exception e) {
                    logger.warn("OCSP signature verification error: {}", e.getMessage());
                    return new OcspResult(RevocationStatus.UNKNOWN, "OCSP signature check failed");
                }

                // 验证 OCSP 响应中的 nonce 与请求一致，防止重放攻击
                Extension respNonceExt = basicResp.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce);
                if (respNonceExt != null) {
                    byte[] respNonceValue = respNonceExt.getExtnValue().getOctets();
                    byte[] reqNonceValue = nonceExtension.getExtnValue().getOctets();
                    if (!java.util.Arrays.equals(reqNonceValue, respNonceValue)) {
                        logger.warn("OCSP response nonce mismatch - possible replay attack");
                        return new OcspResult(RevocationStatus.UNKNOWN, "OCSP nonce mismatch");
                    }
                    logger.debug("OCSP response nonce verified");
                } else {
                    logger.debug("OCSP response does not contain nonce extension");
                }

                SingleResp[] responses = basicResp.getResponses();

                // 查找匹配的证书状态
                for (SingleResp singleResp : responses) {
                    if (singleResp.getCertID().equals(certId)) {
                        CertificateStatus certStatus = singleResp.getCertStatus();

                        if (certStatus == CertificateStatus.GOOD) {
                            logger.debug("OCSP: Certificate status is GOOD");
                            return new OcspResult(RevocationStatus.GOOD, null);
                        } else if (certStatus instanceof RevokedStatus) {
                            RevokedStatus revokedStatus = (RevokedStatus) certStatus;
                            String reason = "Revoked at " + revokedStatus.getRevocationTime();
                            if (revokedStatus.hasRevocationReason()) {
                                reason += ", reason: " + revokedStatus.getRevocationReason();
                            }
                            logger.warn("OCSP: Certificate is REVOKED - {}", reason);
                            return new OcspResult(RevocationStatus.REVOKED, reason);
                        } else if (certStatus instanceof UnknownStatus) {
                            logger.debug("OCSP: Certificate status is UNKNOWN");
                            return new OcspResult(RevocationStatus.UNKNOWN, "OCSP status unknown");
                        }
                    }
                }

                return new OcspResult(RevocationStatus.UNKNOWN, "No matching certificate in OCSP response");
            }
        } catch (Exception e) {
            logger.warn("OCSP check failed: {}", e.getMessage());
            return new OcspResult(RevocationStatus.UNKNOWN, "OCSP error: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 获取 OCSP 响应状态消息
     */
    private String getOcspStatusMessage(int status) {
        switch (status) {
            case OCSPResp.SUCCESSFUL:
                return "SUCCESSFUL";
            case OCSPResp.MALFORMED_REQUEST:
                return "MALFORMED_REQUEST";
            case OCSPResp.INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case OCSPResp.TRY_LATER:
                return "TRY_LATER";
            case OCSPResp.SIG_REQUIRED:
                return "SIG_REQUIRED";
            case OCSPResp.UNAUTHORIZED:
                return "UNAUTHORIZED";
            default:
                return "UNKNOWN(" + status + ")";
        }
    }

    /**
     * 执行 CRL 检查
     */
    private CrlResult checkCrl(X509Certificate certificate, X509Certificate issuerCert) throws Exception {
        // 获取 CRL URL
        String crlUrl = config.crlUrl;
        if (crlUrl == null || crlUrl.isEmpty()) {
            crlUrl = extractCrlUrl(certificate);
        }

        // 先尝试本地 CRL 文件
        if (config.crlPath != null && !config.crlPath.isEmpty()) {
            try {
                return checkLocalCrl(certificate, config.crlPath, issuerCert);
            } catch (Exception e) {
                logger.warn("Local CRL check failed: {}", e.getMessage());
            }
        }

        if (crlUrl == null || crlUrl.isEmpty()) {
            logger.debug("No CRL distribution point found in certificate or config");
            return new CrlResult(RevocationStatus.UNKNOWN, "No CRL URL");
        }

        // 验证 URL 以防止 SSRF 攻击
        if (!isValidRevocationUrl(crlUrl)) {
            logger.warn("Invalid CRL URL rejected: {}", crlUrl);
            return new CrlResult(RevocationStatus.UNKNOWN, "Invalid CRL URL");
        }

        logger.debug("Checking CRL at: {}", crlUrl);
        return checkRemoteCrl(certificate, crlUrl, issuerCert);
    }

    /**
     * 验证吊销检查 URL 以防止 SSRF 攻击
     *
     * @param urlString 要验证的 URL
     * @return 如果 URL 安全可访问则返回 true
     */
    private boolean isValidRevocationUrl(String urlString) {
        try {
            URI uri = URI.create(urlString);
            String scheme = uri.getScheme();
            String host = uri.getHost();

            // 1. 协议白名单 - 仅允许 HTTP/HTTPS
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                logger.warn("Rejected non-HTTP(S) protocol: {}", scheme);
                return false;
            }

            // 2. 主机名验证 - 拒绝私有/内部地址
            if (host == null || host.isEmpty()) {
                return false;
            }

            // 拒绝 localhost/loopback
            if (host.equalsIgnoreCase("localhost") ||
                host.equals("127.0.0.1") ||
                host.equals("::1")) {
                logger.warn("Rejected localhost URL: {}", host);
                return false;
            }

            // 拒绝私有 IP 段
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isAnyLocalAddress() ||
                    addr.isLoopbackAddress() ||
                    addr.isLinkLocalAddress() ||
                    addr.isSiteLocalAddress()) {
                    logger.warn("Rejected private IP address: {}", addr.getHostAddress());
                    return false;
                }

                String hostAddr = addr.getHostAddress().toLowerCase();
                if (hostAddr.startsWith("169.254.")) {
                    logger.warn("Rejected link-local IP: {}", hostAddr);
                    return false;
                }

                if (addr instanceof java.net.Inet4Address) {
                    byte[] octets = addr.getAddress();
                    int first = octets[0] & 0xff;
                    int second = octets[1] & 0xff;

                    if (first == 100 && second >= 64 && second <= 127) {
                        logger.warn("Rejected CGNAT IP: {}", hostAddr);
                        return false;
                    }
                    if (first == 0) {
                        logger.warn("Rejected any-local IP: {}", hostAddr);
                        return false;
                    }
                } else {
                    if (hostAddr.startsWith("fc") || hostAddr.startsWith("fd")) {
                        logger.warn("Rejected unique-local IPv6 address: {}", hostAddr);
                        return false;
                    }
                }
            }
            // 3. 端口验证 - 拒绝非标准端口
            int port = uri.getPort();
            if (port != -1 && port != 80 && port != 443) {
                logger.warn("Rejected non-standard port: {}", port);
                return false;
            }

            return true;

        } catch (Exception e) {
            logger.warn("URL validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查本地 CRL 文件
     */
    private CrlResult checkLocalCrl(X509Certificate certificate, String crlPath, X509Certificate issuerCert) throws Exception {
        try (InputStream is = new java.io.FileInputStream(crlPath)) {
            byte[] crlData = readLimited(is, MAX_RESPONSE_SIZE);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(new java.io.ByteArrayInputStream(crlData));

            // 验证 CRL 签名
            try {
                if (issuerCert != null) {
                    crl.verify(issuerCert.getPublicKey());
                } else {
                    crl.verify(certificate.getPublicKey());
                }
            } catch (Exception e) {
                logger.warn("CRL signature verification failed: {}", e.getMessage());
                if (!config.softFail) {
                    return new CrlResult(RevocationStatus.UNKNOWN, "CRL signature verification failed");
                }
            }

            java.security.cert.X509CRLEntry entry = crl.getRevokedCertificate(certificate.getSerialNumber());
            if (entry != null) {
                return new CrlResult(RevocationStatus.REVOKED,
                    "Serial number " + certificate.getSerialNumber() + " found in CRL");
            }
            return new CrlResult(RevocationStatus.GOOD, null);
        }
    }

    /**
     * 检查远程 CRL
     */
    private CrlResult checkRemoteCrl(X509Certificate certificate, String crlUrl, X509Certificate issuerCert) throws Exception {
        URL url = URI.create(crlUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(config.ocspTimeout);
        conn.setReadTimeout(config.ocspTimeout);

        try (InputStream is = conn.getInputStream()) {
            byte[] crlData = readLimited(is, MAX_RESPONSE_SIZE);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509CRL crl = (X509CRL) cf.generateCRL(new java.io.ByteArrayInputStream(crlData));

            // 验证 CRL 签名：优先使用 issuer 公钥，回退到证书自身公钥（自签名场景）
            try {
                if (issuerCert != null) {
                    crl.verify(issuerCert.getPublicKey());
                } else {
                    crl.verify(certificate.getPublicKey());
                }
                logger.debug("CRL signature verified");
            } catch (Exception e) {
                logger.warn("CRL signature verification failed: {}", e.getMessage());
                if (!config.softFail) {
                    return new CrlResult(RevocationStatus.UNKNOWN, "CRL signature verification failed");
                }
            }

            java.security.cert.X509CRLEntry entry = crl.getRevokedCertificate(certificate.getSerialNumber());
            if (entry != null) {
                return new CrlResult(RevocationStatus.REVOKED,
                    "Serial number " + certificate.getSerialNumber() + " found in CRL");
            }
            return new CrlResult(RevocationStatus.GOOD, null);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 从证书 AIA 扩展中提取 OCSP URL (RFC 5280)
     * 使用正确的 ASN.1 解析而非字符串搜索
     */
    private String extractOcspUrl(X509Certificate certificate) {
        try {
            byte[] aiaExtension = certificate.getExtensionValue("1.3.6.1.5.5.7.1.1");
            if (aiaExtension == null) {
                return null;
            }

            // 使用 Bouncy Castle 解析 ASN.1 结构
            ASN1Primitive derObject = ASN1Primitive.fromByteArray(aiaExtension);
            DEROctetString derOctetString = (DEROctetString) derObject;
            ASN1Primitive derObject2 = ASN1Primitive.fromByteArray(derOctetString.getOctets());

            AuthorityInformationAccess aia = AuthorityInformationAccess.getInstance(derObject2);
            AccessDescription[] descriptions = aia.getAccessDescriptions();

            for (AccessDescription desc : descriptions) {
                if (desc.getAccessMethod().equals(AccessDescription.id_ad_ocsp)) {
                    GeneralName location = desc.getAccessLocation();
                    if (location.getTagNo() == GeneralName.uniformResourceIdentifier) {
                        ASN1IA5String uri = (ASN1IA5String) location.getName();
                        return uri.getString();
                    }
                }
            }
            return null;

        } catch (Exception e) {
            logger.debug("Failed to extract OCSP URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从证书 CDP 扩展中提取 CRL URL (RFC 5280)
     * 使用正确的 ASN.1 解析而非字符串搜索
     */
    private String extractCrlUrl(X509Certificate certificate) {
        try {
            byte[] cdpExtension = certificate.getExtensionValue("2.5.29.31");
            if (cdpExtension == null) {
                return null;
            }

            // 使用 Bouncy Castle 解析 ASN.1 结构
            ASN1Primitive derObject = ASN1Primitive.fromByteArray(cdpExtension);
            DEROctetString derOctetString = (DEROctetString) derObject;
            ASN1Primitive derObject2 = ASN1Primitive.fromByteArray(derOctetString.getOctets());

            CRLDistPoint distPoint = CRLDistPoint.getInstance(derObject2);
            DistributionPoint[] distributionPoints = distPoint.getDistributionPoints();

            for (DistributionPoint dp : distributionPoints) {
                DistributionPointName dpName = dp.getDistributionPoint();
                if (dpName != null && dpName.getType() == DistributionPointName.FULL_NAME) {
                    GeneralNames generalNames = GeneralNames.getInstance(dpName.getName());
                    GeneralName[] names = generalNames.getNames();

                    for (GeneralName name : names) {
                        if (name.getTagNo() == GeneralName.uniformResourceIdentifier) {
                            ASN1IA5String uri = (ASN1IA5String) name.getName();
                            return uri.getString();
                        }
                    }
                }
            }
            return null;

        } catch (Exception e) {
            logger.debug("Failed to extract CRL URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 缓存结果
     */
    private void cacheResult(String fingerprint, boolean revoked, String reason) {
        if (config.cacheEnabled && fingerprint != null) {
            revocationCache.put(fingerprint, new CacheEntry(revoked, reason));
            logger.debug("Cached revocation status for {}: revoked={}",
                fingerprint.substring(0, Math.min(16, fingerprint.length())) + "...", revoked);
        }
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        revocationCache.invalidateAll();
        logger.info("Revocation cache cleared");
    }

    /**
     * 获取缓存大小
     */
    public long getCacheSize() {
        return revocationCache.estimatedSize();
    }

    /**
     * 读取输入流，限制最大大小，防止恶意超大响应导致 OOM
     */
    private static byte[] readLimited(InputStream is, int maxSize) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int totalRead = 0;
        int n;
        while ((n = is.read(buf)) != -1) {
            totalRead += n;
            if (totalRead > maxSize) {
                throw new IOException("Response exceeds maximum allowed size of " + maxSize + " bytes");
            }
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * 吊销状态枚举
     */
    private enum RevocationStatus {
        GOOD,       // 证书有效
        REVOKED,    // 证书已吊销
        UNKNOWN     // 无法确定状态
    }

    /**
     * OCSP 检查结果
     */
    private static class OcspResult {
        final RevocationStatus status;
        final String reason;

        OcspResult(RevocationStatus status, String reason) {
            this.status = status;
            this.reason = reason;
        }
    }

    /**
     * CRL 检查结果
     */
    private static class CrlResult {
        final RevocationStatus status;
        final String reason;

        CrlResult(RevocationStatus status, String reason) {
            this.status = status;
            this.reason = reason;
        }
    }
}
