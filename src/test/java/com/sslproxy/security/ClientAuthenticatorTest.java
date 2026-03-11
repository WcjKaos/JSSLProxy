package com.sslproxy.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigInteger;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * ClientAuthenticator 单元测试
 */
class ClientAuthenticatorTest {

    @Mock
    private X509TrustManager defaultTrustManager;

    @Mock
    private X509Certificate validCert;

    @Mock
    private X509Certificate expiredCert;

    @Mock
    private X509Certificate blacklistedCert;

    @Mock
    private X509Certificate weakRsaCert;

    @Mock
    private X509Certificate sha1Cert;

    private static final String VALID_FINGERPRINT = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
    private static final String BLACKLISTED_FINGERPRINT = "FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF";
    private static final String WHITELISTED_FINGERPRINT = "11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00";

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // 设置有效证书 mock（2048-bit RSA + SHA256withRSA）
        RSAPublicKey strongKey = mock(RSAPublicKey.class);
        when(strongKey.getAlgorithm()).thenReturn("RSA");
        when(strongKey.getModulus()).thenReturn(BigInteger.ONE.shiftLeft(2047)); // 2048 bits

        when(validCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Valid Client, O=Test"));
        when(validCert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test CA, O=Test"));
        when(validCert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 365L * 86400000L));
        when(validCert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(validCert.getEncoded()).thenReturn(("VALID_CERT_" + VALID_FINGERPRINT).getBytes());
        when(validCert.getPublicKey()).thenReturn(strongKey);
        when(validCert.getSigAlgName()).thenReturn("SHA256withRSA");
        doNothing().when(validCert).checkValidity();

        // 设置过期证书 mock
        when(expiredCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Expired Client, O=Test"));
        when(expiredCert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test CA, O=Test"));
        when(expiredCert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(expiredCert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 365L * 86400000L));
        when(expiredCert.getEncoded()).thenReturn(("EXPIRED_CERT_DATA").getBytes());
        doThrow(new CertificateExpiredException("Expired")).when(expiredCert).checkValidity();

        // 设置黑名单证书 mock
        when(blacklistedCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Blacklisted Client, O=Test"));
        when(blacklistedCert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test CA, O=Test"));
        when(blacklistedCert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 365L * 86400000L));
        when(blacklistedCert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(blacklistedCert.getEncoded()).thenReturn(("BLACKLIST_CERT_" + BLACKLISTED_FINGERPRINT).getBytes());
        doNothing().when(blacklistedCert).checkValidity();

        // 设置 1024-bit RSA 弱密钥证书 mock
        RSAPublicKey weakKey = mock(RSAPublicKey.class);
        when(weakKey.getAlgorithm()).thenReturn("RSA");
        when(weakKey.getModulus()).thenReturn(BigInteger.ONE.shiftLeft(1023)); // 1024 bits

        when(weakRsaCert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=Weak Client, O=Test"));
        when(weakRsaCert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test CA, O=Test"));
        when(weakRsaCert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 365L * 86400000L));
        when(weakRsaCert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(weakRsaCert.getEncoded()).thenReturn("WEAK_RSA_CERT".getBytes());
        when(weakRsaCert.getPublicKey()).thenReturn(weakKey);
        when(weakRsaCert.getSigAlgName()).thenReturn("SHA256withRSA");
        doNothing().when(weakRsaCert).checkValidity();

        // 设置 SHA-1 签名证书 mock（RSA key 强度合格，但签名算法弱）
        RSAPublicKey strongKeyForSha1 = mock(RSAPublicKey.class);
        when(strongKeyForSha1.getAlgorithm()).thenReturn("RSA");
        when(strongKeyForSha1.getModulus()).thenReturn(BigInteger.ONE.shiftLeft(2047));

        when(sha1Cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=SHA1 Client, O=Test"));
        when(sha1Cert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test CA, O=Test"));
        when(sha1Cert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 365L * 86400000L));
        when(sha1Cert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(sha1Cert.getEncoded()).thenReturn("SHA1_CERT".getBytes());
        when(sha1Cert.getPublicKey()).thenReturn(strongKeyForSha1);
        when(sha1Cert.getSigAlgName()).thenReturn("SHA1withRSA");
        doNothing().when(sha1Cert).checkValidity();
    }

    // ========== 基本构造函数测试 ==========

    @Test
    void testConstructor_WithDefaultTrustManager() {
        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        assertNotNull(auth);
        assertEquals(0, auth.getWhitelistSize());
        assertEquals(0, auth.getBlacklistSize());
    }

    @Test
    void testConstructor_WithFullConfig() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add(WHITELISTED_FINGERPRINT);
        Set<String> blacklist = new HashSet<>();
        blacklist.add(BLACKLISTED_FINGERPRINT);

        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager, whitelist, blacklist, true, true);
        assertEquals(1, auth.getWhitelistSize());
        assertEquals(1, auth.getBlacklistSize());
    }

    // ========== checkClientTrusted 测试 ==========

    @Test
    void testCheckClientTrusted_NullChain_ThrowsException() {
        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        assertThrows(CertificateException.class, () -> auth.checkClientTrusted(null, "RSA"));
    }

    @Test
    void testCheckClientTrusted_EmptyChain_ThrowsException() {
        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        assertThrows(CertificateException.class, () -> auth.checkClientTrusted(new X509Certificate[0], "RSA"));
    }

    @Test
    void testCheckClientTrusted_ExpiredCert_ThrowsException() {
        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, true, false);

        X509Certificate[] chain = new X509Certificate[]{expiredCert};
        CertificateException ex = assertThrows(CertificateException.class,
                () -> auth.checkClientTrusted(chain, "RSA"));
        assertTrue(ex.getMessage().contains("expired"));
    }

    @Test
    void testCheckClientTrusted_BlacklistedCert_ThrowsException() {
        Set<String> blacklist = new HashSet<>();
        // 计算 blacklistedCert 的实际指纹并添加到黑名单
        String fingerprint = com.sslproxy.util.CertificateUtil.getCertificateFingerprint(blacklistedCert);
        blacklist.add(fingerprint);

        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, blacklist, false, false);

        X509Certificate[] chain = new X509Certificate[]{blacklistedCert};
        CertificateException ex = assertThrows(CertificateException.class,
                () -> auth.checkClientTrusted(chain, "RSA"));
        assertTrue(ex.getMessage().contains("blacklist"));
    }

    @Test
    void testCheckClientTrusted_NotInWhitelist_ThrowsException() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add(WHITELISTED_FINGERPRINT);  // 一个不匹配的指纹

        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, whitelist, null, false, false);

        X509Certificate[] chain = new X509Certificate[]{validCert};
        CertificateException ex = assertThrows(CertificateException.class,
                () -> auth.checkClientTrusted(chain, "RSA"));
        assertTrue(ex.getMessage().contains("whitelist"));
    }

    @Test
    void testCheckClientTrusted_InWhitelist_Succeeds() throws Exception {
        String fingerprint = com.sslproxy.util.CertificateUtil.getCertificateFingerprint(validCert);
        Set<String> whitelist = new HashSet<>();
        whitelist.add(fingerprint);

        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, whitelist, null, false, false);

        X509Certificate[] chain = new X509Certificate[]{validCert};
        // 不应抛出异常
        auth.checkClientTrusted(chain, "RSA");
    }

    @Test
    void testCheckClientTrusted_ValidCertNoWhitelist_Succeeds() throws Exception {
        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, false, false);

        X509Certificate[] chain = new X509Certificate[]{validCert};
        // 不应抛出异常
        auth.checkClientTrusted(chain, "RSA");
    }

    @Test
    void testCheckClientTrusted_WithChainValidation() throws Exception {
        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, false, true);

        X509Certificate[] chain = new X509Certificate[]{validCert};
        auth.checkClientTrusted(chain, "RSA");

        // 验证 defaultTrustManager 被调用
        verify(defaultTrustManager).checkClientTrusted(chain, "RSA");
    }

    @Test
    void testCheckClientTrusted_ChainValidationFails_ThrowsException() throws Exception {
        doThrow(new CertificateException("Chain validation failed"))
                .when(defaultTrustManager).checkClientTrusted(any(), eq("RSA"));

        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, false, true);

        X509Certificate[] chain = new X509Certificate[]{validCert};
        assertThrows(CertificateException.class, () -> auth.checkClientTrusted(chain, "RSA"));
    }

    // ========== 白名单操作测试 ==========

    @Test
    void testAddToWhitelist() {
        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        assertEquals(0, auth.getWhitelistSize());

        auth.addToWhitelist(VALID_FINGERPRINT);
        assertEquals(1, auth.getWhitelistSize());
    }

    @Test
    void testAddToWhitelist_NullOrEmpty_Ignored() {
        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        auth.addToWhitelist(null);
        auth.addToWhitelist("");
        assertEquals(0, auth.getWhitelistSize());
    }

    @Test
    void testRemoveFromWhitelist() {
        Set<String> whitelist = new HashSet<>();
        whitelist.add(VALID_FINGERPRINT);

        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, whitelist, null, true, true);
        assertEquals(1, auth.getWhitelistSize());

        auth.removeFromWhitelist(VALID_FINGERPRINT);
        assertEquals(0, auth.getWhitelistSize());
    }

    // ========== 黑名单操作测试 ==========

    @Test
    void testAddToBlacklist() {
        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        assertEquals(0, auth.getBlacklistSize());

        auth.addToBlacklist(BLACKLISTED_FINGERPRINT);
        assertEquals(1, auth.getBlacklistSize());
    }

    @Test
    void testAddToBlacklist_NullOrEmpty_Ignored() {
        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        auth.addToBlacklist(null);
        auth.addToBlacklist("");
        assertEquals(0, auth.getBlacklistSize());
    }

    @Test
    void testRemoveFromBlacklist() {
        Set<String> blacklist = new HashSet<>();
        blacklist.add(BLACKLISTED_FINGERPRINT);

        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, blacklist, true, true);
        assertEquals(1, auth.getBlacklistSize());

        auth.removeFromBlacklist(BLACKLISTED_FINGERPRINT);
        assertEquals(0, auth.getBlacklistSize());
    }

    // ========== checkServerTrusted 测试 ==========

    @Test
    void testCheckServerTrusted_DelegatesToDefaultTrustManager() throws Exception {
        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        X509Certificate[] chain = new X509Certificate[]{validCert};

        auth.checkServerTrusted(chain, "RSA");

        verify(defaultTrustManager).checkServerTrusted(chain, "RSA");
    }

    @Test
    void testCheckServerTrusted_NullTrustManager_ThrowsException() {
        ClientAuthenticator auth = new ClientAuthenticator(null);
        X509Certificate[] chain = new X509Certificate[]{validCert};

        // 无 trustManager 时应抛出 CertificateException
        assertThrows(java.security.cert.CertificateException.class, () -> {
            auth.checkServerTrusted(chain, "RSA");
        });
    }

    // ========== getAcceptedIssuers 测试 ==========

    @Test
    void testGetAcceptedIssuers_DelegatesToDefaultTrustManager() {
        X509Certificate[] issuers = new X509Certificate[]{validCert};
        when(defaultTrustManager.getAcceptedIssuers()).thenReturn(issuers);

        ClientAuthenticator auth = new ClientAuthenticator(defaultTrustManager);
        X509Certificate[] result = auth.getAcceptedIssuers();

        assertArrayEquals(issuers, result);
    }

    @Test
    void testGetAcceptedIssuers_NullTrustManager_ReturnsEmptyArray() {
        ClientAuthenticator auth = new ClientAuthenticator(null);
        X509Certificate[] result = auth.getAcceptedIssuers();

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    // ========== 密钥强度校验测试（T-13）==========

    @Test
    void should_rejectCert_when_rsaKeyTooWeak() {
        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, false, false);

        X509Certificate[] chain = new X509Certificate[]{weakRsaCert};
        CertificateException ex = assertThrows(CertificateException.class,
                () -> auth.checkClientTrusted(chain, "RSA"));
        assertTrue(ex.getMessage().contains("too weak"), "Should report key as too weak: " + ex.getMessage());
    }

    @Test
    void should_rejectCert_when_sha1SignatureAlgorithm() {
        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, false, false);

        X509Certificate[] chain = new X509Certificate[]{sha1Cert};
        CertificateException ex = assertThrows(CertificateException.class,
                () -> auth.checkClientTrusted(chain, "RSA"));
        assertTrue(ex.getMessage().contains("weak signature algorithm"),
                "Should report weak signature: " + ex.getMessage());
    }

    @Test
    void should_acceptCert_when_rsa2048AndSha256() throws Exception {
        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, false, false);

        X509Certificate[] chain = new X509Certificate[]{validCert};
        // SHA256withRSA 2048-bit — should not throw
        auth.checkClientTrusted(chain, "RSA");
    }

    @Test
    void should_respectCustomMinimumKeyBits() {
        // 设置最低要求为 4096，则 2048-bit 证书应被拒绝
        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, false, false, 4096, null, null);

        X509Certificate[] chain = new X509Certificate[]{validCert}; // 2048-bit
        CertificateException ex = assertThrows(CertificateException.class,
                () -> auth.checkClientTrusted(chain, "RSA"));
        assertTrue(ex.getMessage().contains("too weak"));
    }

    @Test
    void should_rejectCert_when_md5SignatureAlgorithm() throws Exception {
        RSAPublicKey strongKey = mock(RSAPublicKey.class);
        when(strongKey.getAlgorithm()).thenReturn("RSA");
        when(strongKey.getModulus()).thenReturn(BigInteger.ONE.shiftLeft(2047));

        X509Certificate md5Cert = mock(X509Certificate.class);
        when(md5Cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=MD5 Client, O=Test"));
        when(md5Cert.getIssuerX500Principal()).thenReturn(new X500Principal("CN=Test CA, O=Test"));
        when(md5Cert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 365L * 86400000L));
        when(md5Cert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 86400000L));
        when(md5Cert.getEncoded()).thenReturn("MD5_CERT".getBytes());
        when(md5Cert.getPublicKey()).thenReturn(strongKey);
        when(md5Cert.getSigAlgName()).thenReturn("MD5withRSA");
        doNothing().when(md5Cert).checkValidity();

        ClientAuthenticator auth = new ClientAuthenticator(
                defaultTrustManager, null, null, false, false);

        X509Certificate[] chain = new X509Certificate[]{md5Cert};
        CertificateException ex = assertThrows(CertificateException.class,
                () -> auth.checkClientTrusted(chain, "RSA"));
        assertTrue(ex.getMessage().contains("weak signature algorithm"));
    }
}
