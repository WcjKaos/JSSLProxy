package com.sslproxy.management.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.SslContextHolder;
import com.sslproxy.metrics.ProxyMetrics;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

/**
 * Security-focused tests for admin API responses.
 */
class AdminApiHandlerSecurityTest {

    @Test
    void should_notExposeSensitiveExceptionMessage_when_certReloadFails() throws Exception {
        SslContextHolder sslContextHolder = mock(SslContextHolder.class);
        doThrow(new IllegalStateException("password=secret, keyFile=C:/private/server.key"))
                .when(sslContextHolder).reload();

        AdminApiHandler handler = new AdminApiHandler(
                new ProxyConfig(),
                null,
                null,
                null,
                null,
                sslContextHolder,
                new AuditLogger(),
                ProxyMetrics.noop());
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/admin/reload?target=certs"));

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);

        String body = response.content().toString(StandardCharsets.UTF_8);
        assertThat(body).contains("Certificate reload failed");
        assertThat(body).doesNotContain("secret");
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("server.key");

        response.release();
        channel.finishAndReleaseAll();
    }
}
