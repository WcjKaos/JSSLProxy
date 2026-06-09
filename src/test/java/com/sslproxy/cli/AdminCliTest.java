package com.sslproxy.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AdminCliTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldPrintHelp() {
        CliResult result = run(new String[]{"--help"}, Map.of());

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("jsslproxyctl");
        assertThat(result.out()).contains("blacklist list|add|remove");
    }

    @Test
    void shouldRequireTokenForAdminCommands() {
        CliResult result = run(new String[]{"stats"}, Map.of("JSSLPROXY_ADMIN_URL", "http://127.0.0.1:9"));

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("Admin token required");
    }

    @Test
    void shouldUseEnvUrlAndToken() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server = startServer(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"activeConnections\":2}");
        }, "/admin/stats");

        CliResult result = run(new String[]{"stats", "--json"}, env("env-token"));

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("\"activeConnections\": 2");
        assertThat(auth.get()).isEqualTo("Bearer env-token");
    }

    @Test
    void shouldPreferCommandLineUrlAndTokenOverEnvironment() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server = startServer(exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"activeConnections\":3}");
        }, "/admin/stats");

        CliResult result = run(new String[]{
                "--url", "http://127.0.0.1:" + server.getAddress().getPort(),
                "--token", "flag-token",
                "stats",
                "--json"
        }, Map.of(
                "JSSLPROXY_ADMIN_URL", "http://127.0.0.1:9",
                "JSSLPROXY_ADMIN_TOKEN", "env-token"
        ));

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("\"activeConnections\": 3");
        assertThat(auth.get()).isEqualTo("Bearer flag-token");
    }

    @Test
    void shouldSendFingerprintInRequestBodyWithoutRewritingIt() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = startServer(exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"status\":\"success\"}");
        }, "/admin/blacklist");

        String fingerprint = "AA:bb:CC:dd:EE:ff:00:11:22:33:44:55:66:77:88:99:"
                + "AA:bb:CC:dd:EE:ff:00:11:22:33:44:55:66:77:88:99";
        CliResult result = run(new String[]{"BLACKLIST", "ADD", fingerprint, "--yes"}, env("env-token"));

        assertThat(result.code()).isZero();
        assertThat(method.get()).isEqualTo("POST");
        assertThat(body.get()).isEqualTo(fingerprint);
    }

    @Test
    void shouldRefuseWriteWithoutYes() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, "{}"), "/admin/reload");

        CliResult result = run(new String[]{"reload", "certs"}, env("env-token"));

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.err()).contains("requires --yes");
    }

    @Test
    void shouldPrintAuditNoticeForHumanWriteOutput() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, "{\"status\":\"reloaded\"}"), "/admin/reload");

        CliResult result = run(new String[]{"reload", "certs", "--yes"}, env("env-token"));

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("Audit: management API records this operation.");
    }

    @Test
    void shouldKeepJsonWriteOutputMachineReadable() throws Exception {
        server = startServer(exchange -> respond(exchange, 200, "{\"status\":\"reloaded\"}"), "/admin/reload");

        CliResult result = run(new String[]{"reload", "certs", "--yes", "--json"}, env("env-token"));

        assertThat(result.code()).isZero();
        assertThat(result.out()).doesNotContain("Audit:");
        assertThat(result.out()).contains("\"status\": \"reloaded\"");
    }

    @Test
    void shouldMapUnauthorizedToAuthExitCode() throws Exception {
        server = startServer(exchange -> respond(exchange, 401, "Unauthorized"), "/admin/stats");

        CliResult result = run(new String[]{"stats"}, env("bad-token"));

        assertThat(result.code()).isEqualTo(3);
        assertThat(result.err()).contains("HTTP 401");
    }

    @Test
    void shouldRedactTokenFromErrorOutput() throws Exception {
        server = startServer(exchange -> respond(exchange, 401,
                "Bad token flag-token; Authorization: Bearer flag-token; token=flag-token"),
                "/admin/stats");

        CliResult result = run(new String[]{"--token", "flag-token", "stats"}, env("env-token"));

        assertThat(result.code()).isEqualTo(3);
        assertThat(result.err()).contains("<redacted>");
        assertThat(result.err()).doesNotContain("flag-token");
    }

    @Test
    void shouldCombineStatusResponses() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/ready", exchange -> respond(exchange, 503, "{\"status\":\"DRAINING\"}"));
        server.createContext("/admin/info", exchange -> respond(exchange, 200, "{\"configHash\":\"abcd1234\"}"));
        server.start();

        CliResult result = run(new String[]{"status", "--json"}, env("env-token"));

        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("\"health\"");
        assertThat(result.out()).contains("\"statusCode\": 503");
        assertThat(result.out()).contains("abcd1234");
    }

    @Test
    void shouldSendRejectedCertLimitAsQueryParameter() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server = startServer(exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, "{\"rejected\":[]}");
        }, "/admin/rejected-certs");

        CliResult result = run(new String[]{"rejected-certs", "--limit", "5", "--json"}, env("env-token"));

        assertThat(result.code()).isZero();
        assertThat(query.get()).isEqualTo("limit=5");
    }

    @Test
    void shouldUseSeparateDrainTimeoutParameter() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server = startServer(exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, "{\"status\":\"draining\"}");
        }, "/admin/drain");

        CliResult result = run(new String[]{
                "drain", "start", "--timeout", "2", "--drain-timeout", "30", "--yes"
        }, env("env-token"));

        assertThat(result.code()).isZero();
        assertThat(query.get()).isEqualTo("timeout=30");
    }

    @Test
    void shouldEncodeConnectionFingerprintQueryParameter() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server = startServer(exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "{\"terminated\":1}");
        }, "/admin/connections");

        String fingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";
        CliResult result = run(new String[]{"connections", "terminate", fingerprint, "--yes"}, env("env-token"));

        assertThat(result.code()).isZero();
        assertThat(rawQuery.get()).startsWith("fingerprint=");
        assertThat(URLDecoder.decode(rawQuery.get().substring("fingerprint=".length()), StandardCharsets.UTF_8))
                .isEqualTo(fingerprint);
    }

    private Map<String, String> env(String token) {
        return Map.of(
                "JSSLPROXY_ADMIN_URL", "http://127.0.0.1:" + server.getAddress().getPort(),
                "JSSLPROXY_ADMIN_TOKEN", token
        );
    }

    private CliResult run(String[] args, Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = AdminCli.execute(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                env);
        return new CliResult(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private HttpServer startServer(ExchangeHandler handler, String path) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext(path, handler::handle);
        s.start();
        return s;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CliResult(int code, String out, String err) {
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
