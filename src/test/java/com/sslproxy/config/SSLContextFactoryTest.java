package com.sslproxy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sslproxy.integration.TestCertificates;

import io.netty.handler.ssl.SslContext;

/**
 * SSLContextFactory 单元测试
 */
class SSLContextFactoryTest {

    private static TestCertificates testCerts;
    private static Path serverKeystorePath;
    private static Path truststorePath;

    @BeforeAll
    static void setUp() throws Exception {
        testCerts = new TestCertificates();
        testCerts.initializeCA();
        serverKeystorePath = testCerts.generateServerKeyStore("localhost");
        testCerts.generateClientKeyStore("test-client");
        truststorePath = testCerts.generateTrustStore();
    }

    @AfterAll
    static void tearDown() {
        if (testCerts != null) {
            testCerts.cleanup();
        }
    }

    @Test
    void should_createServerSslContext_when_validPkcs12Keystore() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();

        SslContext ctx = SSLContextFactory.createServerSslContext(ssl, null, null, null);

        assertThat(ctx).isNotNull();
        assertThat(ctx.isServer()).isTrue();
    }

    @Test
    void should_throwException_when_sslConfigIsNull() {
        assertThatThrownBy(() -> SSLContextFactory.createServerSslContext(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ssl.keystore required");
    }

    @Test
    void should_throwException_when_keystoreIsNull() {
        ProxyConfig.Ssl ssl = new ProxyConfig.Ssl();
        ssl.keystore = null;

        assertThatThrownBy(() -> SSLContextFactory.createServerSslContext(ssl, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ssl.keystore required");
    }

    @Test
    void should_createServerSslContext_when_clientAuthNone() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.clientAuth = "NONE";

        SslContext ctx = SSLContextFactory.createServerSslContext(ssl, null, null, null);

        assertThat(ctx).isNotNull();
        assertThat(ctx.isServer()).isTrue();
    }

    @Test
    void should_createServerSslContext_when_clientAuthRequired() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.clientAuth = "REQUIRED";

        SslContext ctx = SSLContextFactory.createServerSslContext(ssl, null, null, null);

        assertThat(ctx).isNotNull();
    }

    @Test
    void should_createServerSslContext_when_clientAuthOptional() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.clientAuth = "OPTIONAL";

        SslContext ctx = SSLContextFactory.createServerSslContext(ssl, null, null, null);

        assertThat(ctx).isNotNull();
    }

    @Test
    void should_applyProtocols_when_specified() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.protocols = Arrays.asList("TLSv1.3", "TLSv1.2");

        SslContext ctx = SSLContextFactory.createServerSslContext(ssl, null, null, null);

        assertThat(ctx).isNotNull();
    }

    @Test
    void should_applyCiphers_when_specified() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.ciphers = Arrays.asList("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");

        SslContext ctx = SSLContextFactory.createServerSslContext(ssl, null, null, null);

        assertThat(ctx).isNotNull();
    }

    @Test
    void should_createServerSslContext_when_whitelistConfigured() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.whitelistFingerprints = List.of(
                "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99");

        SslContext ctx = SSLContextFactory.createServerSslContext(ssl, null, null, null);

        assertThat(ctx).isNotNull();
        assertThat(SSLContextFactory.getServerAuthenticator()).isNotNull();
    }

    @Test
    void should_createServerSslContext_when_blacklistConfigured() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.blacklistFingerprints = List.of(
                "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99");

        SslContext ctx = SSLContextFactory.createServerSslContext(ssl, null, null, null);

        assertThat(ctx).isNotNull();
    }

    @Test
    void should_createClientSslContext_when_noConfig() throws Exception {
        SslContext ctx = SSLContextFactory.createClientSslContext(null);

        assertThat(ctx).isNotNull();
        assertThat(ctx.isClient()).isTrue();
    }

    @Test
    void should_createClientSslContext_when_systemTrustStrategy() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.backendTrustStrategy = "SYSTEM";

        SslContext ctx = SSLContextFactory.createClientSslContext(ssl);

        assertThat(ctx).isNotNull();
        assertThat(ctx.isClient()).isTrue();
    }

    @Test
    void should_createClientSslContext_when_customTrustStrategy() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.backendTrustStrategy = "CUSTOM";

        SslContext ctx = SSLContextFactory.createClientSslContext(ssl);

        assertThat(ctx).isNotNull();
        assertThat(ctx.isClient()).isTrue();
    }

    @Test
    void should_createClientSslContext_when_bothTrustStrategy() throws Exception {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.backendTrustStrategy = "BOTH";

        SslContext ctx = SSLContextFactory.createClientSslContext(ssl);

        assertThat(ctx).isNotNull();
        assertThat(ctx.isClient()).isTrue();
    }

    @Test
    void should_throwException_when_customTrustWithoutTruststore() {
        ProxyConfig.Ssl ssl = new ProxyConfig.Ssl();
        // No keystore - skip key manager step, go straight to trust strategy
        ssl.backendTrustStrategy = "CUSTOM";
        // no truststore configured

        assertThatThrownBy(() -> SSLContextFactory.createClientSslContext(ssl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CUSTOM requires ssl.truststore");
    }

    @Test
    void should_throwException_when_invalidTrustStrategy() {
        ProxyConfig.Ssl ssl = createBasicSslConfig();
        ssl.backendTrustStrategy = "INVALID";

        assertThatThrownBy(() -> SSLContextFactory.createClientSslContext(ssl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported backendTrustStrategy");
    }

    @Test
    void should_throwException_when_keystoreFileNotFound() {
        ProxyConfig.Ssl ssl = new ProxyConfig.Ssl();
        ssl.keystore = new ProxyConfig.KeystoreConfig();
        ssl.keystore.path = "/nonexistent/path/keystore.p12";
        ssl.keystore.password = "test";
        ssl.keystore.type = "PKCS12";
        ssl.clientAuth = "NONE";

        assertThatThrownBy(() -> SSLContextFactory.createServerSslContext(ssl, null, null, null))
                .isInstanceOf(Exception.class);
    }

    private ProxyConfig.Ssl createBasicSslConfig() {
        ProxyConfig.Ssl ssl = new ProxyConfig.Ssl();
        ssl.keystore = new ProxyConfig.KeystoreConfig();
        ssl.keystore.path = serverKeystorePath.toString();
        ssl.keystore.password = TestCertificates.getDefaultPasswordString();
        ssl.keystore.type = "PKCS12";

        ssl.truststore = new ProxyConfig.KeystoreConfig();
        ssl.truststore.path = truststorePath.toString();
        ssl.truststore.password = TestCertificates.getDefaultPasswordString();
        ssl.truststore.type = "PKCS12";

        ssl.clientAuth = "NONE";
        ssl.protocols = Arrays.asList("TLSv1.3", "TLSv1.2");
        return ssl;
    }
}
