package com.sslproxy.integration;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.server.ProxyServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class HttpsHealthCheckIT {
    private final HttpClient managementClient = HttpClient.newHttpClient();
    private static TestCertificates certificates;
    private static Path serverKeystore;
    private static Path truststore;

    private TestBackendServer backend;
    private ProxyServer proxy;
    private Thread proxyThread;
    private int backendPort;
    private int managementPort;

    @BeforeAll
    static void createCertificates() throws Exception {
        certificates = new TestCertificates();
        certificates.initializeCA();
        serverKeystore = certificates.generateServerKeyStore("localhost");
        certificates.generateClientKeyStore("test-client");
        truststore = certificates.generateTrustStore();
    }

    @AfterAll
    static void removeCertificates() {
        certificates.cleanup();
    }

    @BeforeEach
    void startBackend() throws Exception {
        backendPort = IntegrationTestBase.findAvailablePort();
        managementPort = IntegrationTestBase.findAvailablePort();
        backend = new TestBackendServer(backendPort, certificates, serverKeystore);
        backend.start();
    }

    @AfterEach
    void stopServers() throws Exception {
        if (proxy != null) {
            proxy.shutdown();
        }
        if (proxyThread != null) {
            proxyThread.interrupt();
            proxyThread.join(2000);
        }
        backend.stop();
    }

    @Test
    void should_markBackendUp_when_httpsCertificateIsTrustedAndHostnameMatches() throws Exception {
        startProxy("localhost", "CUSTOM");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(backend.getRequestCount()).isPositive();
            assertThat(readinessStatus()).isEqualTo(200);
        });
    }

    @Test
    void should_markBackendDown_when_trustedCertificateHostnameDoesNotMatch() throws Exception {
        startProxy("127.0.0.1", "CUSTOM");

        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(readinessStatus()).isEqualTo(503);
            assertThat(backend.getRequestCount()).isZero();
        });
    }

    @Test
    void should_markBackendDown_when_httpsCertificateIsNotTrusted() throws Exception {
        startProxy("localhost", "SYSTEM");

        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(readinessStatus()).isEqualTo(503);
            assertThat(backend.getRequestCount()).isZero();
        });
    }

    @Test
    void should_markBackendDown_when_tlsBackendClosesWithoutAnHttpResponse() throws Exception {
        backend.withFailure(true);
        startProxy("localhost", "CUSTOM");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(backend.getRequestCount()).isPositive();
            assertThat(readinessStatus()).isEqualTo(503);
        });
    }

    @Test
    void should_markBackendDown_when_httpsResponseExceedsProbeTimeout() throws Exception {
        backend.withResponseDelay(1500);
        startProxy("localhost", "CUSTOM");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(backend.getRequestCount()).isPositive();
            assertThat(readinessStatus()).isEqualTo(503);
        });
    }

    private void startProxy(String backendHost, String trustStrategy) throws Exception {
        ProxyConfig config = new ProxyConfig();
        config.server = new ProxyConfig.Server();
        config.server.host = "127.0.0.1";
        config.server.port = IntegrationTestBase.findAvailablePort();
        config.target = new ProxyConfig.Target();
        config.target.host = backendHost;
        config.target.port = backendPort;
        config.target.protocol = "https";
        config.target.healthCheck = new ProxyConfig.HealthCheckConfig();
        config.target.healthCheck.enabled = true;
        config.target.healthCheck.intervalSeconds = 1;
        config.target.healthCheck.timeoutMs = 500;
        config.target.healthCheck.unhealthyThreshold = 1;
        config.target.healthCheck.healthyThreshold = 1;
        config.ssl = new ProxyConfig.Ssl();
        config.ssl.keystore = keystore(serverKeystore);
        config.ssl.truststore = keystore(truststore);
        config.ssl.clientAuth = "NONE";
        config.ssl.protocols = List.of("TLSv1.3", "TLSv1.2");
        config.ssl.backendTrustStrategy = trustStrategy;
        config.performance = new ProxyConfig.Performance();
        config.performance.workerThreads = 2;
        config.performance.backendPool = new ProxyConfig.BackendPoolConfig();
        config.performance.backendPool.enabled = false;
        config.management = new ProxyConfig.Management();
        config.management.enabled = true;
        config.management.host = "127.0.0.1";
        config.management.port = managementPort;
        config.management.authToken = "https-health-test-token";
        config.metrics = new ProxyConfig.Metrics();
        config.metrics.enabled = false;
        proxy = new ProxyServer(config);
        AtomicReference<Exception> startupFailure = new AtomicReference<>();
        proxyThread = new Thread(() -> {
            try {
                proxy.start();
            } catch (Exception error) {
                startupFailure.set(error);
            }
        }, "https-health-proxy");
        proxyThread.setDaemon(true);
        proxyThread.start();
        await().atMost(15, TimeUnit.SECONDS).until(() -> proxy.isRunning() || startupFailure.get() != null);
        if (startupFailure.get() != null) {
            throw startupFailure.get();
        }
    }

    private ProxyConfig.KeystoreConfig keystore(Path path) {
        ProxyConfig.KeystoreConfig config = new ProxyConfig.KeystoreConfig();
        config.path = path.toString();
        config.password = TestCertificates.getDefaultPasswordString();
        config.type = "PKCS12";
        return config;
    }

    private int readinessStatus() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + managementPort + "/ready"))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        return managementClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
