package com.sslproxy.management.handler;

import com.sslproxy.audit.AuditEventType;
import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ManagementAuthHandler 单元测试
 */
class ManagementAuthHandlerTest {

    @Test
    void testAdminEndpointWithoutToken_Returns401() {
        ProxyConfig config = createConfig(true, "secret-token");
        AuditLogger auditLogger = mock(AuditLogger.class);
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, auditLogger));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/admin/whitelist");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        verify(auditLogger).log(eq(AuditEventType.ADMIN_UNAUTHORIZED), argThat((Map<String, Object> details) ->
                "FAILURE".equals(details.get("result"))
                        && "/admin/whitelist".equals(details.get("uri"))
                        && details.containsKey("sourceIp")));
        response.release();
    }

    @Test
    void testAdminEndpointWithCorrectBearerToken_Passes() {
        ProxyConfig config = createConfig(true, "secret-token");
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/admin/whitelist");
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer secret-token");
        channel.writeInbound(request);

        // Request should pass through (no response written by auth handler)
        FullHttpResponse response = channel.readOutbound();
        assertNull(response);
        // Request should be forwarded
        FullHttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded);
        forwarded.release();
    }

    @Test
    void testAdminEndpointWithCorrectXAdminToken_Passes() {
        ProxyConfig config = createConfig(true, "secret-token");
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/admin/blacklist");
        request.headers().set("X-Admin-Token", "secret-token");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNull(response);
        FullHttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded);
        forwarded.release();
    }

    @Test
    void testAdminEndpointWithWrongToken_Returns401() {
        ProxyConfig config = createConfig(true, "secret-token");
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/admin/whitelist");
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer wrong-token");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        response.release();
    }

    @Test
    void testHealthEndpoint_NoAuthRequired() {
        ProxyConfig config = createConfig(true, "secret-token");
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/health");
        channel.writeInbound(request);

        // No auth response, request passes through
        FullHttpResponse response = channel.readOutbound();
        assertNull(response);
        FullHttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded);
        forwarded.release();
    }

    @Test
    void testMetricsEndpoint_NoAuthRequired() {
        ProxyConfig config = createConfig(true, "secret-token");
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/metrics");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNull(response);
        FullHttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded);
        forwarded.release();
    }

    @Test
    void testAdminUiShell_NoAuthRequired() {
        ProxyConfig config = createConfig(true, "secret-token");
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/admin/ui");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNull(response);
        FullHttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded);
        assertEquals("/admin/ui", forwarded.uri());
        forwarded.release();
    }

    @Test
    void testAdminUiStaticResource_NoAuthRequired() {
        ProxyConfig config = createConfig(true, "secret-token");
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/admin/ui/app.js");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNull(response);
        FullHttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded);
        assertEquals("/admin/ui/app.js", forwarded.uri());
        forwarded.release();
    }

    @Test
    void testAuthDisabled_AdminEndpointPasses() {
        ProxyConfig config = createConfig(false, "secret-token");
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/admin/whitelist");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNull(response);
        FullHttpRequest forwarded = channel.readInbound();
        assertNotNull(forwarded);
        forwarded.release();
    }

    @Test
    void testNoTokenConfigured_Returns403() {
        ProxyConfig config = createConfig(true, null);
        EmbeddedChannel channel = new EmbeddedChannel(new ManagementAuthHandler(config, new com.sslproxy.audit.AuditLogger()));

        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/admin/whitelist");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        response.release();
    }

    private ProxyConfig createConfig(boolean authEnabled, String authToken) {
        ProxyConfig config = new ProxyConfig();
        config.management = new ProxyConfig.Management();
        config.management.authEnabled = authEnabled;
        config.management.authToken = authToken;
        return config;
    }
}
