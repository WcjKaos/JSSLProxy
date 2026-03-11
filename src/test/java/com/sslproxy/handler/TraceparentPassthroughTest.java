package com.sslproxy.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.sslproxy.config.ProxyConfig;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

/**
 * traceparent / tracestate header 透传测试
 * <p>
 * 验证 W3C Trace Context header 在代理转发时被原样保留。
 * </p>
 */
class TraceparentPassthroughTest {

    @Test
    void should_preserveTraceparent_when_headerPresent() throws Exception {
        ProxyFrontendHandler handler = createHandler();

        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.headers().set("Host", "example.com");

        handler.modifyRequestHeaders(request);

        assertThat(request.headers().get("traceparent"))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void should_preserveTracestate_when_headerPresent() throws Exception {
        ProxyFrontendHandler handler = createHandler();

        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set("traceparent",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.headers().set("tracestate", "vendor1=opaque1,vendor2=opaque2");
        request.headers().set("Host", "example.com");

        handler.modifyRequestHeaders(request);

        assertThat(request.headers().get("tracestate"))
                .isEqualTo("vendor1=opaque1,vendor2=opaque2");
    }

    @Test
    void should_notCreateTraceparent_when_headerAbsent() throws Exception {
        ProxyFrontendHandler handler = createHandler();

        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set("Host", "example.com");

        handler.modifyRequestHeaders(request);

        assertThat(request.headers().get("traceparent")).isNull();
        assertThat(request.headers().get("tracestate")).isNull();
    }

    @Test
    void should_preserveCustomHeaders_when_modifying() throws Exception {
        ProxyFrontendHandler handler = createHandler();

        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/test");
        request.headers().set("X-Custom-Header", "custom-value");
        request.headers().set("Authorization", "Bearer token123");
        request.headers().set("Host", "example.com");

        handler.modifyRequestHeaders(request);

        assertThat(request.headers().get("X-Custom-Header")).isEqualTo("custom-value");
        assertThat(request.headers().get("Authorization")).isEqualTo("Bearer token123");
        // Proxy-added headers should also be present
        assertThat(request.headers().get("X-Forwarded-For")).isNotNull();
        assertThat(request.headers().get("X-Forwarded-Proto")).isEqualTo("https");
    }

    private ProxyFrontendHandler createHandler() throws Exception {
        ProxyConfig config = new ProxyConfig();
        config.target = new ProxyConfig.Target();
        config.target.host = "localhost";
        config.target.port = 8080;
        config.target.protocol = "http";
        config.ssl = new ProxyConfig.Ssl();
        config.ssl.protocols = Arrays.asList("TLSv1.3");

        ProxyFrontendHandler handler = new ProxyFrontendHandler(config);

        // Set clientAddress via reflection (normally set in channelActive)
        Field clientAddressField = ProxyFrontendHandler.class.getDeclaredField("clientAddress");
        clientAddressField.setAccessible(true);
        clientAddressField.set(handler, "/127.0.0.1:12345");

        return handler;
    }
}
