package com.sslproxy.security;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sslproxy.config.ProxyConfig.RevocationConfig;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.Security;

/**
 * 证书吊销检查器安全测试
 * <p>
 * 测试 SSRF 防护、URL 验证和签名验证功能
 * </p>
 */
public class CertificateRevocationCheckerSecurityTest {

    @BeforeAll
    public static void setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    /**
     * 测试 SSRF 防护 - 拒绝 localhost URL
     */
    @Test
    public void testSsrfProtection_RejectsLocalhostUrl() throws Exception {
        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        // 使用反射调用私有方法
        Method method = CertificateRevocationChecker.class.getDeclaredMethod("isValidRevocationUrl", String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(checker, "http://localhost/ocsp"));
        assertFalse((Boolean) method.invoke(checker, "http://127.0.0.1/ocsp"));
        assertFalse((Boolean) method.invoke(checker, "http://[::1]/ocsp"));
    }

    /**
     * 测试 SSRF 防护 - 拒绝私有 IP URL
     */
    @Test
    public void testSsrfProtection_RejectsPrivateIpUrl() throws Exception {
        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod("isValidRevocationUrl", String.class);
        method.setAccessible(true);

        // RFC 1918 私有地址
        assertFalse((Boolean) method.invoke(checker, "http://10.0.0.1/ocsp"));
        assertFalse((Boolean) method.invoke(checker, "http://172.16.0.1/ocsp"));
        assertFalse((Boolean) method.invoke(checker, "http://192.168.1.1/ocsp"));
    }

    /**
     * 测试 SSRF 防护 - 拒绝 CGNAT/ULA IP URL
     */
    @Test
    public void testSsrfProtection_RejectsCgnatAndUla() throws Exception {
        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod("isValidRevocationUrl", String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(checker, "http://100.64.0.1/ocsp"));
        assertFalse((Boolean) method.invoke(checker, "http://[fd00::1]/ocsp"));
    }

    /**
     * 测试 SSRF 防护 - 拒绝云元数据端点
     */
    @Test
    public void testSsrfProtection_RejectsCloudMetadataUrl() throws Exception {
        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod("isValidRevocationUrl", String.class);
        method.setAccessible(true);

        // AWS/Azure 元数据端点
        assertFalse((Boolean) method.invoke(checker, "http://169.254.169.254/latest/meta-data/"));
        assertFalse((Boolean) method.invoke(checker, "http://169.254.0.1/metadata"));
    }

    /**
     * 测试 SSRF 防护 - 拒绝 file:// 协议
     */
    @Test
    public void testSsrfProtection_RejectsFileProtocol() throws Exception {
        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod("isValidRevocationUrl", String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(checker, "file:///etc/passwd"));
        assertFalse((Boolean) method.invoke(checker, "ftp://example.com/crl"));
        assertFalse((Boolean) method.invoke(checker, "gopher://example.com/"));
    }

    /**
     * 测试 SSRF 防护 - 拒绝非标准端口
     */
    @Test
    public void testSsrfProtection_RejectsNonStandardPort() throws Exception {
        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod("isValidRevocationUrl", String.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(checker, "http://example.com:8080/ocsp"));
        assertFalse((Boolean) method.invoke(checker, "https://example.com:8443/ocsp"));
        assertFalse((Boolean) method.invoke(checker, "http://example.com:22/ocsp"));
    }

    /**
     * 测试 SSRF 防护 - 允许公共 HTTPS URL
     */
    @Test
    public void testSsrfProtection_AllowsPublicHttpsUrl() throws Exception {
        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod("isValidRevocationUrl", String.class);
        method.setAccessible(true);

        // 使用真实的公共 OCSP/CRL 服务器
        assertTrue((Boolean) method.invoke(checker, "http://ocsp.digicert.com/"));
        assertTrue((Boolean) method.invoke(checker, "http://crl.globalsign.com/"));
        assertTrue((Boolean) method.invoke(checker, "https://ocsp.sectigo.com/"));
    }

    /**
     * 测试 URL 提取 - 正确的 ASN.1 解析（OCSP）
     */
    @Test
    public void testUrlExtraction_ProperAsn1ParsingOcsp() throws Exception {
        // 创建包含 OCSP URL 的证书
        String ocspUrl = "http://ocsp.example.com/";
        X509Certificate cert = createCertificateWithOcspUrl(ocspUrl);

        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        // 使用反射调用私有方法
        Method method = CertificateRevocationChecker.class.getDeclaredMethod("extractOcspUrl", X509Certificate.class);
        method.setAccessible(true);

        String extractedUrl = (String) method.invoke(checker, cert);
        assertEquals(ocspUrl, extractedUrl);
    }

    /**
     * 测试 URL 提取 - 正确的 ASN.1 解析（CRL）
     */
    @Test
    public void testUrlExtraction_ProperAsn1ParsingCrl() throws Exception {
        // 创建包含 CRL URL 的证书
        String crlUrl = "http://crl.example.com/";
        X509Certificate cert = createCertificateWithCrlUrl(crlUrl);

        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        // 使用反射调用私有方法
        Method method = CertificateRevocationChecker.class.getDeclaredMethod("extractCrlUrl", X509Certificate.class);
        method.setAccessible(true);

        String extractedUrl = (String) method.invoke(checker, cert);
        assertEquals(crlUrl, extractedUrl);
    }

    /**
     * 测试 URL 提取 - 拒绝恶意 URL
     */
    @Test
    public void testUrlExtraction_RejectsMaliciousUrl() throws Exception {
        // 创建包含恶意 OCSP URL 的证书
        String maliciousUrl = "http://169.254.169.254/latest/meta-data/";
        X509Certificate cert = createCertificateWithOcspUrl(maliciousUrl);

        RevocationConfig config = createTestConfig();
        config.ocspEnabled = true;
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        // 使用反射调用 checkOcsp 方法
        Method method = CertificateRevocationChecker.class.getDeclaredMethod(
            "checkOcsp", X509Certificate.class, X509Certificate.class);
        method.setAccessible(true);

        // 应该返回 UNKNOWN 状态（URL 被拒绝）
        Object result = method.invoke(checker, cert, cert);
        assertNotNull(result);

        // 验证结果状态为 UNKNOWN（通过反射访问 final 字段）
        java.lang.reflect.Field statusField = result.getClass().getDeclaredField("status");
        statusField.setAccessible(true);
        Object status = statusField.get(result);
        assertEquals("UNKNOWN", status.toString());
    }

    /**
     * 测试 OCSP 缺少 issuer 时返回 UNKNOWN
     */
    @Test
    public void testOcspMissingIssuerReturnsUnknown() throws Exception {
        String ocspUrl = "http://ocsp.example.com/";
        X509Certificate cert = createCertificateWithOcspUrl(ocspUrl);

        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod(
            "checkOcsp", X509Certificate.class, X509Certificate.class);
        method.setAccessible(true);

        Object result = method.invoke(checker, cert, null);
        assertNotNull(result);

        java.lang.reflect.Field statusField = result.getClass().getDeclaredField("status");
        statusField.setAccessible(true);
        Object status = statusField.get(result);
        assertEquals("UNKNOWN", status.toString());
    }

    /**
     * 测试没有 OCSP URL 的证书
     */
    @Test
    public void testUrlExtraction_NullForMissingOcspUrl() throws Exception {
        X509Certificate cert = createCertificateWithoutExtensions();

        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod("extractOcspUrl", X509Certificate.class);
        method.setAccessible(true);

        String extractedUrl = (String) method.invoke(checker, cert);
        assertNull(extractedUrl);
    }

    /**
     * 测试没有 CRL URL 的证书
     */
    @Test
    public void testUrlExtraction_NullForMissingCrlUrl() throws Exception {
        X509Certificate cert = createCertificateWithoutExtensions();

        RevocationConfig config = createTestConfig();
        CertificateRevocationChecker checker = new CertificateRevocationChecker(config);

        Method method = CertificateRevocationChecker.class.getDeclaredMethod("extractCrlUrl", X509Certificate.class);
        method.setAccessible(true);

        String extractedUrl = (String) method.invoke(checker, cert);
        assertNull(extractedUrl);
    }

    // ========== 辅助方法 ==========

    private RevocationConfig createTestConfig() {
        RevocationConfig config = new RevocationConfig();
        config.enabled = true;
        config.ocspEnabled = true;
        config.crlEnabled = true;
        config.softFail = false;
        config.cacheEnabled = false;
        config.ocspTimeout = 5000;
        return config;
    }

    private X509Certificate createCertificateWithOcspUrl(String ocspUrl) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X500Principal subject = new X500Principal("CN=Test Certificate");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

        // 添加 AIA 扩展（OCSP URL）
        GeneralName ocspName = new GeneralName(GeneralName.uniformResourceIdentifier, new DERIA5String(ocspUrl));
        AccessDescription ocspAccess = new AccessDescription(AccessDescription.id_ad_ocsp, ocspName);
        AuthorityInformationAccess aia = new AuthorityInformationAccess(ocspAccess);
        certBuilder.addExtension(
            org.bouncycastle.asn1.x509.Extension.authorityInfoAccess,
            false,
            aia);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider("BC")
            .build(keyPair.getPrivate());

        X509CertificateHolder certHolder = certBuilder.build(signer);
        return new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder);
    }

    private X509Certificate createCertificateWithCrlUrl(String crlUrl) throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X500Principal subject = new X500Principal("CN=Test Certificate");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

        // 添加 CDP 扩展（CRL URL）
        GeneralName crlName = new GeneralName(GeneralName.uniformResourceIdentifier, new DERIA5String(crlUrl));
        GeneralNames crlNames = new GeneralNames(crlName);
        DistributionPointName dpName = new DistributionPointName(crlNames);
        DistributionPoint dp = new DistributionPoint(dpName, null, null);
        CRLDistPoint crlDistPoint = new CRLDistPoint(new DistributionPoint[]{dp});

        certBuilder.addExtension(
            org.bouncycastle.asn1.x509.Extension.cRLDistributionPoints,
            false,
            crlDistPoint);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider("BC")
            .build(keyPair.getPrivate());

        X509CertificateHolder certHolder = certBuilder.build(signer);
        return new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder);
    }

    private X509Certificate createCertificateWithoutExtensions() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        X500Principal subject = new X500Principal("CN=Test Certificate");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider("BC")
            .build(keyPair.getPrivate());

        X509CertificateHolder certHolder = certBuilder.build(signer);
        return new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder);
    }
}
