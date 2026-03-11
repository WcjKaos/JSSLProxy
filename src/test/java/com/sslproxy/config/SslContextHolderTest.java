package com.sslproxy.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.integration.TestCertificates;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.security.DynamicCertificateManager;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

/**
 * SslContextHolder 单元测试
 */
class SslContextHolderTest {

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

    private SslContextHolder createHolder(ProxyConfig.Ssl ssl) throws Exception {
        return new SslContextHolder(ssl, ProxyMetrics.noop(), new AuditLogger(),
                new DynamicCertificateManager(), null);
    }

    @Test
    void should_initialize_when_validConfig() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();

        SslContextHolder holder = createHolder(ssl);

        assertThat(holder).isNotNull();
        assertThat(holder.getSslContext()).isNotNull();
    }

    @Test
    void should_createDistinctInstances_when_constructedTwice() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();

        SslContextHolder first = createHolder(ssl);
        SslContextHolder second = createHolder(ssl);

        assertThat(first).isNotSameAs(second);
    }

    @Test
    void should_returnServerSslContext_when_getSslContext() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();
        SslContextHolder holder = createHolder(ssl);

        SslContext ctx = holder.getSslContext();

        assertThat(ctx).isNotNull();
        assertThat(ctx.isServer()).isTrue();
    }

    @Test
    void should_createNewSslHandler_when_newHandlerCalled() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();
        SslContextHolder holder = createHolder(ssl);

        SslHandler handler = holder.newHandler(UnpooledByteBufAllocator.DEFAULT);

        assertThat(handler).isNotNull();
        handler.engine().closeOutbound();
    }

    @Test
    void should_incrementReloadCount_when_reloaded() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();
        SslContextHolder holder = createHolder(ssl);

        // Initial load counts as reload #1
        long initialCount = holder.getReloadCount();
        assertThat(initialCount).isEqualTo(1);

        holder.reload();

        assertThat(holder.getReloadCount()).isEqualTo(initialCount + 1);
    }

    @Test
    void should_updateLastReloadTime_when_reloaded() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();
        SslContextHolder holder = createHolder(ssl);

        long beforeReload = System.currentTimeMillis();
        holder.reload();
        long afterReload = System.currentTimeMillis();

        long lastReload = holder.getLastReloadTime();
        assertThat(lastReload).isBetween(beforeReload, afterReload);
    }

    @Test
    void should_returnCertificateInfo_when_requested() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();
        SslContextHolder holder = createHolder(ssl);

        List<Map<String, Object>> certInfo = holder.getCertificateInfo();

        assertThat(certInfo).isNotEmpty();
        Map<String, Object> firstCert = certInfo.get(0);
        assertThat(firstCert).containsKey("subject");
        assertThat(firstCert).containsKey("issuer");
        assertThat(firstCert).containsKey("serialNumber");
        assertThat(firstCert).containsKey("notBefore");
        assertThat(firstCert).containsKey("notAfter");
        assertThat(firstCert).containsKey("fingerprint");
    }

    @Test
    void should_returnUpStatus_when_certValid() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();
        SslContextHolder holder = createHolder(ssl);

        String status = holder.getCertificateStatus();

        assertThat(status).isEqualTo("UP");
    }

    @Test
    void should_preserveOldContext_when_reloadFails() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();
        SslContextHolder holder = createHolder(ssl);

        SslContext originalContext = holder.getSslContext();
        assertThat(originalContext).isNotNull();

        // Corrupt the keystore path to make reload fail
        ssl.keystore.path = "/nonexistent/keystore.p12";

        // Reload should fail but keep old context
        try {
            holder.reload();
        } catch (Exception ignored) {
            // Expected
        }

        // Old context should still be available
        assertThat(holder.getSslContext()).isNotNull();
    }

    @Test
    void should_notBeWatching_when_notStarted() throws Exception {
        ProxyConfig.Ssl ssl = createSslConfig();
        SslContextHolder holder = createHolder(ssl);

        assertThat(holder.isWatching()).isFalse();
    }

    private ProxyConfig.Ssl createSslConfig() {
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
