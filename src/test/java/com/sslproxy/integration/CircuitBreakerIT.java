package com.sslproxy.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sslproxy.config.ProxyConfig;

@DisplayName("熔断器响应生命周期集成测试（连接池）")
class CircuitBreakerIT extends IntegrationTestBase {
    private static final java.net.http.HttpClient MANAGEMENT_CLIENT =
            java.net.http.HttpClient.newHttpClient();

    private int managementPort;

    @Override
    protected ProxyConfig createProxyConfig() {
        ProxyConfig config = super.createProxyConfig();
        config.connection.timeout = 2000;
        config.performance.workerThreads = 2;
        config.performance.backendPool = new ProxyConfig.BackendPoolConfig();
        config.performance.backendPool.enabled = true;
        config.performance.backendPool.maxConnections = 4;
        config.management.enabled = true;
        config.management.host = "127.0.0.1";
        managementPort = findAvailablePort();
        config.management.port = managementPort;
        config.management.authEnabled = true;
        config.management.authToken = "circuit-breaker-test";
        config.metrics.enabled = true;
        config.metrics.includeJvmMetrics = false;
        config.circuitBreaker = new ProxyConfig.CircuitBreaker();
        config.circuitBreaker.enabled = true;
        config.circuitBreaker.failureRateThreshold = 50;
        config.circuitBreaker.slidingWindowSize = 4;
        config.circuitBreaker.minimumNumberOfCalls = 2;
        config.circuitBreaker.waitDurationInOpenState = 2;
        config.circuitBreaker.permittedNumberOfCallsInHalfOpenState = 2;
        config.circuitBreaker.slowCallDurationThreshold = 500;
        config.circuitBreaker.slowCallRateThreshold = 50;
        return config;
    }

    @Test
    void should_keepCircuitClosed_when_normalRequestsComplete() throws Exception {
        for (int requestIndex = 0; requestIndex < 5; requestIndex++) {
            assertThat(sendGetRequest("/success/" + requestIndex).statusCode).isEqualTo(200);
        }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String metrics = fetchMetrics();
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                    .isEqualTo(5);
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\"")).isZero();
            assertThat(metric(metrics, "proxy_circuitbreaker_state")).isZero();
        });
        assertThat(backendServer.getRequestCount()).isEqualTo(5);
    }

    @Test
    void should_openCircuitAndRejectWithoutForwarding_when_backendReturnsServerErrors() throws Exception {
        backendServer.withResponseStatus(500);
        assertThat(sendGetRequest("/failure/first").statusCode).isEqualTo(500);
        assertThat(sendGetRequest("/failure/second").statusCode).isEqualTo(500);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String metrics = fetchMetrics();
            assertThat(metric(metrics, "proxy_circuitbreaker_state")).isEqualTo(1);
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\""))
                    .isEqualTo(2);
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\"")).isZero();
        });

        assertThat(sendGetRequest("/failure/rejected").statusCode).isEqualTo(503);
        assertThat(backendServer.getRequestCount()).isEqualTo(2);
        assertThat(metric(fetchMetrics(), "proxy_circuitbreaker_calls_total", "result=\"rejected\""))
                .isEqualTo(1);
    }

    @Test
    void should_countClientErrorsAsSuccessfulBackendCalls_when_backendReturnsFourHundred() throws Exception {
        backendServer.withResponseStatus(404);
        for (int requestIndex = 0; requestIndex < 4; requestIndex++) {
            assertThat(sendGetRequest("/not-found/" + requestIndex).statusCode).isEqualTo(404);
        }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String metrics = fetchMetrics();
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                    .isEqualTo(4);
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\"")).isZero();
            assertThat(metric(metrics, "proxy_circuitbreaker_state")).isZero();
        });
    }

    @Test
    void should_waitForFinalResponse_when_backendSendsEarlyHints() throws Exception {
        CompletableFuture<Void> responseGate = new CompletableFuture<>();
        backendServer.withResponseGate(responseGate).withInterimResponse(103);
        CompletableFuture<HttpResponse> pendingResponse = CompletableFuture.supplyAsync(() -> {
            try {
                return sendGetRequest("/deferred-final-response");
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        });

        try {
            await().atMost(5, TimeUnit.SECONDS).until(() -> backendServer.getRequestCount() == 1);
            String metrics = fetchMetrics();
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\"")).isZero();
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\"")).isZero();
            assertThat(pendingResponse).isNotDone();
        } finally {
            responseGate.complete(null);
        }

        assertThat(pendingResponse.get(10, TimeUnit.SECONDS).statusCode).isEqualTo(200);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(metric(fetchMetrics(), "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                        .isEqualTo(1));
    }

    @Test
    void should_openCircuit_when_completedResponsesExceedSlowCallThreshold() throws Exception {
        backendServer.withResponseDelay(800);
        assertThat(sendGetRequest("/slow/first").statusCode).isEqualTo(200);
        assertThat(sendGetRequest("/slow/second").statusCode).isEqualTo(200);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String metrics = fetchMetrics();
            assertThat(metric(metrics, "resilience4j_circuitbreaker_slow_calls",
                    "kind=\"successful\"", "name=\"backend-proxy\"")).isEqualTo(2);
            assertThat(metric(metrics, "proxy_circuitbreaker_state")).isEqualTo(1);
        });
        assertThat(sendGetRequest("/slow/rejected").statusCode).isEqualTo(503);
        assertThat(backendServer.getRequestCount()).isEqualTo(2);
    }

    @Test
    void should_recordOneFailure_when_backendClosesWithoutResponse() throws Exception {
        backendServer.withFailure(true);
        try (SSLSocket client = (SSLSocket) testCerts.createTrustAllSSLContext()
                .getSocketFactory().createSocket("localhost", proxyPort)) {
            client.setSoTimeout(5000);
            client.startHandshake();
            client.getOutputStream().write((
                    "GET /closed-without-response HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            ).getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            BufferedReader response = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = response.readLine();
            assertThat(statusLine == null || statusLine.startsWith("HTTP/1.1 502")).isTrue();
        }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String metrics = fetchMetrics();
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\"")).isEqualTo(1);
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\"")).isZero();
        });
        assertThat(backendServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    void should_preserveResponseOrderAndCountEachCallOnce_when_requestsArePipelinedBeforeBackendConnects()
            throws Exception {
        CompletableFuture<Void> responseGate = new CompletableFuture<>();
        backendServer.withResponseGate(responseGate);
        try (SSLSocket client = createRawClient()) {
            String requests = "GET /pipeline/first HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
                    + "GET /pipeline/second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            client.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            await().atMost(5, TimeUnit.SECONDS).until(() -> backendServer.getRequestCount() == 2);
            assertThat(metric(fetchMetrics(), "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                    .isZero();
            responseGate.complete(null);

            BufferedReader responses = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            HttpResponse firstResponse = readRawResponse(responses);
            HttpResponse secondResponse = readRawResponse(responses);
            assertThat(firstResponse.statusCode).isEqualTo(200);
            assertThat(firstResponse.body).contains("Path: /pipeline/first");
            assertThat(secondResponse.statusCode).isEqualTo(200);
            assertThat(secondResponse.body).contains("Path: /pipeline/second");
        } finally {
            responseGate.complete(null);
        }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String metrics = fetchMetrics();
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                    .isEqualTo(2);
            assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\"")).isZero();
        });
    }

    @Test
    void should_cancelWithoutRecordingFailureOrReusingDirtyConnection_when_clientDisconnects()
            throws Exception {
        CompletableFuture<Void> cancelledResponseGate = new CompletableFuture<>();
        CompletableFuture<Void> freshResponseGate = new CompletableFuture<>();
        backendServer.withResponseGate(cancelledResponseGate);
        try {
            try (SSLSocket client = createRawClient()) {
                client.getOutputStream().write((
                        "GET /cancelled-old HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
                ).getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                await().atMost(5, TimeUnit.SECONDS).until(() -> backendServer.getRequestCount() == 1);
            }
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                String metrics = fetchMetrics();
                assertThat(metric(metrics, "proxy_connections_active")).isZero();
                assertThat(metric(metrics, "proxy_pool_active")).isZero();
                assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                        .isZero();
                assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\"")).isZero();
            });

            backendServer.withResponseGate(freshResponseGate).withResponseBody("Fresh response");
            CompletableFuture<HttpResponse> freshResponse = CompletableFuture.supplyAsync(() -> {
                try {
                    return sendGetRequest("/fresh-client");
                } catch (Exception error) {
                    throw new CompletionException(error);
                }
            });
            await().atMost(5, TimeUnit.SECONDS).until(() -> backendServer.getRequestCount() == 2);
            cancelledResponseGate.complete(null);
            await().during(200, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(freshResponse).isNotDone();
                String metrics = fetchMetrics();
                assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                        .isZero();
                assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\"")).isZero();
            });
            freshResponseGate.complete(null);
            HttpResponse response = freshResponse.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode).isEqualTo(200);
            assertThat(response.body).contains("Fresh response", "Path: /fresh-client")
                    .doesNotContain("Path: /cancelled-old");
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(metric(fetchMetrics(), "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                            .isEqualTo(1));
        } finally {
            cancelledResponseGate.complete(null);
            freshResponseGate.complete(null);
        }
    }

    @Test
    void should_returnGatewayTimeoutAndRecordOneFailure_when_backendNeverCompletesResponse()
            throws Exception {
        CompletableFuture<Void> responseGate = new CompletableFuture<>();
        backendServer.withResponseGate(responseGate);
        try {
            HttpResponse response = sendGetRequest("/never-completes");
            assertThat(response.statusCode).isEqualTo(504);
            assertThat(backendServer.getRequestCount()).isEqualTo(1);
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                String metrics = fetchMetrics();
                assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\""))
                        .isEqualTo(1);
                assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                        .isZero();
            });
            responseGate.complete(null);
            await().during(200, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                String metrics = fetchMetrics();
                assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"failed\""))
                        .isEqualTo(1);
                assertThat(metric(metrics, "proxy_circuitbreaker_calls_total", "result=\"successful\""))
                        .isZero();
            });
        } finally {
            responseGate.complete(null);
        }
    }

    @Test
    void should_closeCircuitAfterHalfOpenProbes_when_backendRecovers() throws Exception {
        backendServer.withResponseStatus(500);
        assertThat(sendGetRequest("/recover/failure-first").statusCode).isEqualTo(500);
        assertThat(sendGetRequest("/recover/failure-second").statusCode).isEqualTo(500);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(metric(fetchMetrics(), "proxy_circuitbreaker_state")).isEqualTo(1));

        backendServer.withResponseStatus(200);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(metric(fetchMetrics(), "proxy_circuitbreaker_state")).isEqualTo(2));
        assertThat(sendGetRequest("/recover/probe-first").statusCode).isEqualTo(200);
        assertThat(metric(fetchMetrics(), "proxy_circuitbreaker_state")).isEqualTo(2);
        assertThat(sendGetRequest("/recover/probe-second").statusCode).isEqualTo(200);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(metric(fetchMetrics(), "proxy_circuitbreaker_state")).isZero());
        assertThat(sendGetRequest("/recover/closed").statusCode).isEqualTo(200);
        assertThat(backendServer.getRequestCount()).isEqualTo(5);
    }

    private SSLSocket createRawClient() throws Exception {
        SSLSocket client = (SSLSocket) testCerts.createTrustAllSSLContext()
                .getSocketFactory().createSocket("localhost", proxyPort);
        client.setSoTimeout(5000);
        client.startHandshake();
        return client;
    }

    private HttpResponse readRawResponse(BufferedReader responses) throws Exception {
        String statusLine = responses.readLine();
        assertThat(statusLine).isNotNull().startsWith("HTTP/1.1 ");
        int status = Integer.parseInt(statusLine.split(" ", 3)[1]);
        int contentLength = -1;
        String header;
        while ((header = responses.readLine()) != null && !header.isEmpty()) {
            if (header.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                contentLength = Integer.parseInt(header.substring("Content-Length:".length()).trim());
            }
        }
        assertThat(contentLength).isNotNegative();
        char[] body = new char[contentLength];
        int received = 0;
        while (received < contentLength) {
            int count = responses.read(body, received, contentLength - received);
            assertThat(count).isPositive();
            received += count;
        }
        return new HttpResponse(status, new String(body));
    }

    private String fetchMetrics() throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + managementPort + "/metrics")
        ).GET().build();
        java.net.http.HttpResponse<String> response = MANAGEMENT_CLIENT.send(
                request, java.net.http.HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static double metric(String metrics, String name, String... labels) {
        return metrics.lines()
                .filter(line -> line.startsWith(name + "{") || line.startsWith(name + " "))
                .filter(line -> Arrays.stream(labels).allMatch(line::contains))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing metric: " + name + Arrays.toString(labels)));
    }
}
