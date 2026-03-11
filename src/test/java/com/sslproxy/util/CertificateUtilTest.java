package com.sslproxy.util;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * CertificateUtil 单元测试
 *
 * 使用 Mockito 模拟 X509Certificate，避免依赖内部 JDK API
 */
class CertificateUtilTest {

    @Mock
    private X509Certificate validCert;

    @Mock
    private X509Certificate expiredCert;

    @Mock
    private X509Certificate expiringSoonCert;

    // 模拟证书的编码字节（用于指纹测试）
    private static final byte[] MOCK_CERT_BYTES_1 = "mock-certificate-content-1".getBytes();
    private static final byte[] MOCK_CERT_BYTES_2 = "mock-certificate-content-2".getBytes();

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // 设置有效证书
        when(validCert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 365L * 86400000L));
        when(validCert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(validCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Test Valid, O=Test Org"));
        when(validCert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test Issuer, O=Test Org"));
        when(validCert.getSerialNumber()).thenReturn(java.math.BigInteger.valueOf(12345));
        when(validCert.getEncoded()).thenReturn(MOCK_CERT_BYTES_1);
        doNothing().when(validCert).checkValidity();

        // 设置已过期证书
        when(expiredCert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(expiredCert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 365L * 86400000L));
        when(expiredCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Test Expired, O=Test Org"));
        when(expiredCert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test Issuer, O=Test Org"));
        when(expiredCert.getSerialNumber()).thenReturn(java.math.BigInteger.valueOf(12346));
        when(expiredCert.getEncoded()).thenReturn(MOCK_CERT_BYTES_2);
        doThrow(new CertificateExpiredException("Certificate expired")).when(expiredCert).checkValidity();

        // 设置即将过期证书（3 天后过期）
        when(expiringSoonCert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 3L * 86400000L));
        when(expiringSoonCert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(expiringSoonCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Test Expiring Soon, O=Test Org"));
        when(expiringSoonCert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test Issuer, O=Test Org"));
        when(expiringSoonCert.getSerialNumber()).thenReturn(java.math.BigInteger.valueOf(12347));
        when(expiringSoonCert.getEncoded()).thenReturn(MOCK_CERT_BYTES_1);
        doNothing().when(expiringSoonCert).checkValidity();
    }

    // ========== isCertificateExpired 测试 ==========

    @Test
    void testIsCertificateExpired_ValidCert_ReturnsFalse() {
        assertFalse(CertificateUtil.isCertificateExpired(validCert));
    }

    @Test
    void testIsCertificateExpired_ExpiredCert_ReturnsTrue() {
        assertTrue(CertificateUtil.isCertificateExpired(expiredCert));
    }

    @Test
    void testIsCertificateExpired_NullCert_ReturnsTrue() {
        assertTrue(CertificateUtil.isCertificateExpired(null));
    }

    // ========== isCertificateExpiringSoon 测试 ==========

    @Test
    void testIsCertificateExpiringSoon_ExpiringSoon_ReturnsTrue() {
        // 3 天后过期，7 天阈值内
        assertTrue(CertificateUtil.isCertificateExpiringSoon(expiringSoonCert, 7));
    }

    @Test
    void testIsCertificateExpiringSoon_NotExpiringSoon_ReturnsFalse() {
        // 1 年后过期，7 天阈值外
        assertFalse(CertificateUtil.isCertificateExpiringSoon(validCert, 7));
    }

    @Test
    void testIsCertificateExpiringSoon_NullCert_ReturnsTrue() {
        assertTrue(CertificateUtil.isCertificateExpiringSoon(null, 7));
    }

    @Test
    void testIsCertificateExpiringSoon_ExactThreshold_ReturnsFalse() {
        // 3 天后过期，3 天阈值刚好不在范围内
        assertFalse(CertificateUtil.isCertificateExpiringSoon(expiringSoonCert, 2));
    }

    // ========== getCertificateFingerprint 测试 ==========

    @Test
    void testGetCertificateFingerprint_ValidCert_ReturnsNonNull() {
        String fingerprint = CertificateUtil.getCertificateFingerprint(validCert);
        assertNotNull(fingerprint);
        // SHA-256 指纹应该是 64 个十六进制字符 + 31 个冒号 = 95 字符
        assertEquals(95, fingerprint.length());
        // 格式检查：应该包含冒号分隔
        assertTrue(fingerprint.contains(":"));
    }

    @Test
    void testGetCertificateFingerprint_NullCert_ReturnsNull() {
        assertNull(CertificateUtil.getCertificateFingerprint(null));
    }

    @Test
    void testGetCertificateFingerprint_DifferentAlgorithm() {
        String sha1 = CertificateUtil.getCertificateFingerprint(validCert, "SHA-1");
        String sha256 = CertificateUtil.getCertificateFingerprint(validCert, "SHA-256");

        assertNotNull(sha1);
        assertNotNull(sha256);
        // SHA-1 是 40 字符 + 19 冒号 = 59，SHA-256 是 64 字符 + 31 冒号 = 95
        assertEquals(59, sha1.length());
        assertEquals(95, sha256.length());
        assertNotEquals(sha1, sha256);
    }

    @Test
    void testGetCertificateFingerprint_SameCertSameFingerprint() {
        String fp1 = CertificateUtil.getCertificateFingerprint(validCert);
        String fp2 = CertificateUtil.getCertificateFingerprint(validCert);
        assertEquals(fp1, fp2);
    }

    @Test
    void testGetCertificateFingerprint_DifferentCertsDifferentFingerprints() {
        String fp1 = CertificateUtil.getCertificateFingerprint(validCert);
        String fp2 = CertificateUtil.getCertificateFingerprint(expiredCert);
        assertNotEquals(fp1, fp2);
    }

    // ========== getCommonName 测试 ==========

    @Test
    void testGetCommonName_ValidCert_ReturnsCN() {
        String cn = CertificateUtil.getCommonName(validCert);
        assertEquals("Test Valid", cn);
    }

    @Test
    void testGetCommonName_NullCert_ReturnsNull() {
        assertNull(CertificateUtil.getCommonName(null));
    }

    // ========== getCertificateInfo 测试 ==========

    @Test
    void testGetCertificateInfo_ValidCert_ContainsAllFields() {
        String info = CertificateUtil.getCertificateInfo(validCert);

        assertTrue(info.contains("Subject:"));
        assertTrue(info.contains("Issuer:"));
        assertTrue(info.contains("Serial:"));
        assertTrue(info.contains("Valid From:"));
        assertTrue(info.contains("Valid Until:"));
        assertTrue(info.contains("Fingerprint (SHA-256):"));
        assertTrue(info.contains("Test Valid"));
    }

    @Test
    void testGetCertificateInfo_NullCert_ReturnsMessage() {
        String info = CertificateUtil.getCertificateInfo(null);
        assertEquals("Certificate is null", info);
    }

    // ========== getCertificateSummary 测试 ==========

    @Test
    void testGetCertificateSummary_ValidCert_ContainsCNAndFingerprint() {
        String summary = CertificateUtil.getCertificateSummary(validCert);

        assertTrue(summary.contains("CN=Test Valid"));
        assertTrue(summary.contains("Fingerprint="));
        // 指纹应该被截断为 16 字符 + "..."
        assertTrue(summary.contains("..."));
    }

    @Test
    void testGetCertificateSummary_NullCert_ReturnsNull() {
        String summary = CertificateUtil.getCertificateSummary(null);
        assertEquals("null", summary);
    }

    // ========== getRemainingValidityDays 测试 ==========

    @Test
    void testGetRemainingValidityDays_ValidCert_ReturnsPositive() {
        long days = CertificateUtil.getRemainingValidityDays(validCert);
        assertTrue(days > 300); // 应该接近 365 天
    }

    @Test
    void testGetRemainingValidityDays_ExpiredCert_ReturnsNegative() {
        long days = CertificateUtil.getRemainingValidityDays(expiredCert);
        assertTrue(days < 0);
    }

    @Test
    void testGetRemainingValidityDays_NullCert_ReturnsMinusOne() {
        long days = CertificateUtil.getRemainingValidityDays(null);
        assertEquals(-1, days);
    }

    @Test
    void testGetRemainingValidityDays_ExpiringSoon() {
        long days = CertificateUtil.getRemainingValidityDays(expiringSoonCert);
        assertTrue(days >= 2 && days <= 4); // 大约 3 天
    }

    // ========== validateCertificateChain 测试 ==========

    @Test
    void testValidateCertificateChain_NullChain_ReturnsFalse() {
        assertFalse(CertificateUtil.validateCertificateChain(null));
    }

    @Test
    void testValidateCertificateChain_EmptyChain_ReturnsFalse() {
        assertFalse(CertificateUtil.validateCertificateChain(new X509Certificate[0]));
    }

    @Test
    void testValidateCertificateChain_SingleCert_ReturnsTrue() {
        // 单证书链总是有效
        assertTrue(CertificateUtil.validateCertificateChain(new X509Certificate[]{validCert}));
    }

    // ========== areCertificatesEqual 测试 ==========

    @Test
    void testAreCertificatesEqual_SameCert_ReturnsTrue() {
        assertTrue(CertificateUtil.areCertificatesEqual(validCert, validCert));
    }

    @Test
    void testAreCertificatesEqual_DifferentCerts_ReturnsFalse() {
        assertFalse(CertificateUtil.areCertificatesEqual(validCert, expiredCert));
    }

    @Test
    void testAreCertificatesEqual_NullCert_ReturnsFalse() {
        assertFalse(CertificateUtil.areCertificatesEqual(null, validCert));
        assertFalse(CertificateUtil.areCertificatesEqual(validCert, null));
        assertFalse(CertificateUtil.areCertificatesEqual(null, null));
    }

    // ========== loadCertificateFromBytes 测试 ==========
    // 注意：loadCertificateFromBytes 需要真实的 DER/PEM 编码证书，使用 mock 无法测试
    // 该方法的测试放到集成测试中进行

    @Test
    void testLoadCertificateFromBytes_InvalidBytes_ThrowsException() {
        byte[] invalidBytes = new byte[]{1, 2, 3, 4, 5};
        assertThrows(Exception.class, () -> CertificateUtil.loadCertificateFromBytes(invalidBytes));
    }

}
