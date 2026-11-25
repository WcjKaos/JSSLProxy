package com.sslproxy.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sslproxy.config.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReadinessIT extends IntegrationTestBase {
    private final HttpClient managementClient = HttpClient.newHttpClient();
    private int managementPort;

    @Override
    protected ProxyConfig createProxyConfig() {
        ProxyConfig config = super.createProxyConfig();
        managementPort = findAvailablePort();
        config.management.enabled = true;
        config.management.host = "127.0.0.1";
        config.management.port = managementPort;
        config.management.authToken = "readiness-test-token";

        ProxyConfig.BackendConfig available = new ProxyConfig.BackendConfig();
        available.host = "localhost";
        available.port = backendPort;
        available.protocol = "http";
        ProxyConfig.BackendConfig unavailable = new ProxyConfig.BackendConfig();
        unavailable.host = "127.0.0.1";
        unavailable.port = findAvailablePort();
        unavailable.protocol = "http";
        config.target.backends = List.of(available, unavailable);
        return config;
    }

    @Test
    void should_reportPartialReadiness_when_onlyOneBackendIsReachable() throws Exception {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            java.net.http.HttpResponse<String> response = readiness();
            assertThat(response.statusCode()).isEqualTo(200);
            JsonObject backend = backendChecks(response);
            assertThat(backend.get("healthy").getAsInt()).isEqualTo(1);
            assertThat(backend.get("total").getAsInt()).isEqualTo(2);
            assertThat(backend.get("status").getAsString()).isEqualTo("PARTIAL");
        });
    }

    @Test
    void should_return503_when_allBackendsBecomeUnreachableWithoutPeriodicChecks() {
        backendServer.stop();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            java.net.http.HttpResponse<String> response = readiness();
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(backendChecks(response).get("healthy").getAsInt()).isZero();
        });
    }

    @Test
    void should_recoverReadiness_when_backendStartsAcceptingConnectionsAgain() throws Exception {
        backendServer.stop();
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(readiness().statusCode()).isEqualTo(503));
        backendServer = createBackendServer();
        backendServer.start();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            java.net.http.HttpResponse<String> response = readiness();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(backendChecks(response).get("healthy").getAsInt()).isEqualTo(1);
        });
    }

    private java.net.http.HttpResponse<String> readiness() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + managementPort + "/ready"))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        return managementClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject backendChecks(java.net.http.HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject()
                .getAsJsonObject("checks").getAsJsonObject("backendTarget");
    }
}
