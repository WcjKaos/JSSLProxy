package com.sslproxy.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin HTTP client for the management API.
 */
public class AdminHttpClient {
    private final URI baseUri;
    private final String token;
    private final Duration timeout;
    private final HttpClient client;

    public AdminHttpClient(String baseUrl, String token, Duration timeout) {
        String normalized = baseUrl == null || baseUrl.isBlank()
                ? "http://localhost:9090"
                : baseUrl.trim().replaceAll("/+$", "");
        this.baseUri = URI.create(normalized);
        this.token = token;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10)
                : timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    public AdminResponse request(String method, String path, String body, boolean authenticated)
            throws IOException, InterruptedException {
        URI uri = baseUri.resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "*/*");

        if (authenticated && token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "text/plain; charset=UTF-8");
        }

        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        builder.method(method, publisher);

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new AdminResponse(response.statusCode(), response.body());
    }

    public record AdminResponse(int statusCode, String body) {
        public boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
