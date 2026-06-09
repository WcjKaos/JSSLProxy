package com.sslproxy.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sslproxy.config.ProxyConfig;

/**
 * End-to-end mTLS admission tests.
 */
@DisplayName("mTLS 集成测试")
class MtlsIntegrationIT extends IntegrationTestBase {

    @Override
    protected ProxyConfig createProxyConfig() {
        ProxyConfig config = super.createProxyConfig();
        config.ssl.clientAuth = "REQUIRED";
        return config;
    }

    @Override
    protected CloseableHttpClient createHttpClient() throws Exception {
        return createHttpClient(testCerts.createClientSSLContext(clientKeystorePath));
    }

    @Test
    @DisplayName("有效客户端证书应允许请求转发")
    void should_forwardRequest_when_clientCertificateTrusted() throws Exception {
        HttpResponse response = sendGetRequest("/mtls/ok");

        assertThat(response.statusCode).isEqualTo(200);
        assertThat(response.body).contains("Path: /mtls/ok");
        assertThat(backendServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("无客户端证书应拒绝连接")
    void should_rejectRequest_when_clientCertificateMissing() throws Exception {
        try (CloseableHttpClient clientWithoutCert = super.createHttpClient()) {
            assertThatThrownBy(() -> executeGet(clientWithoutCert, "/mtls/no-cert"))
                    .isInstanceOf(Exception.class);
        }

        assertThat(backendServer.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("不受信任客户端证书应拒绝连接")
    void should_rejectRequest_when_clientCertificateUntrusted() throws Exception {
        Path untrustedClientKeystore = testCerts.generateClientKeyStore("untrusted-client");

        try (CloseableHttpClient untrustedClient =
                     createHttpClient(testCerts.createClientSSLContext(untrustedClientKeystore))) {
            assertThatThrownBy(() -> executeGet(untrustedClient, "/mtls/untrusted"))
                    .isInstanceOf(Exception.class);
        }

        assertThat(backendServer.getRequestCount()).isZero();
    }

    private void executeGet(CloseableHttpClient client, String path) throws Exception {
        HttpGet request = new HttpGet(getProxyUrl() + path);
        try (CloseableHttpResponse ignored = client.execute(request)) {
            // A successful response would mean the mTLS admission check failed.
        }
    }

    private CloseableHttpClient createHttpClient(SSLContext sslContext) {
        SSLConnectionSocketFactory sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();

        PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslSocketFactory)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(30))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }
}
