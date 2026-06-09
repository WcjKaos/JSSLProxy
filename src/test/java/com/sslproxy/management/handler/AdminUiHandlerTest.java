package com.sslproxy.management.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

class AdminUiHandlerTest {

    @Test
    void shouldServeIndexHtml() {
        EmbeddedChannel channel = new EmbeddedChannel(new AdminUiHandler());

        channel.writeInbound(request(HttpMethod.GET, "/admin/ui"));

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains("text/html");
        assertThat(response.headers().get("Content-Security-Policy"))
                .contains("default-src 'none'")
                .contains("script-src 'self'")
                .contains("style-src 'self'")
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval");
        assertThat(response.headers().get("X-Content-Type-Options")).isEqualTo("nosniff");
        String html = response.content().toString(StandardCharsets.UTF_8);
        assertThat(html)
                .contains("JSSLProxy Admin")
                .contains("id=\"languageSelect\"")
                .contains("value=\"zh-CN\"")
                .contains("data-i18n=\"section.connection\"")
                .contains("class=\"usage-help\"")
                .contains("data-i18n=\"helpPanel.adminToken\"")
                .contains("href=\"/admin/ui/styles.css\"")
                .contains("src=\"/admin/ui/app.js\"")
                .doesNotContain("localStorage")
                .doesNotContain("Authorization: Bearer")
                .doesNotContain("http://")
                .doesNotContain("https://");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldServeJavascriptResource() {
        EmbeddedChannel channel = new EmbeddedChannel(new AdminUiHandler());

        channel.writeInbound(request(HttpMethod.GET, "/admin/ui/app.js"));

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains("application/javascript");
        String script = response.content().toString(StandardCharsets.UTF_8);
        assertThat(script)
                .contains("sessionStorage")
                .contains("jsslproxy.adminLanguage")
                .contains("\"zh-CN\"")
                .contains("JSSLProxy 管理")
                .contains("页面使用说明")
                .contains("MANAGEMENT_TOKEN")
                .contains("languageSelect.addEventListener(\"change\", changeLanguage)")
                .contains("sessionStorage.setItem(LANGUAGE_KEY")
                .contains("headers.Authorization")
                .contains("normalizeFingerprint")
                .contains("withBusy")
                .doesNotContain("localStorage")
                .doesNotContain("?token");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldServeCssResource() {
        EmbeddedChannel channel = new EmbeddedChannel(new AdminUiHandler());

        channel.writeInbound(request(HttpMethod.GET, "/admin/ui/styles.css"));

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains("text/css");
        assertThat(response.content().toString(StandardCharsets.UTF_8))
                .contains("prefers-reduced-motion")
                .doesNotContain("@import")
                .doesNotContain("url(http");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldServeHeadWithoutBody() {
        EmbeddedChannel channel = new EmbeddedChannel(new AdminUiHandler());

        channel.writeInbound(request(HttpMethod.HEAD, "/admin/ui"));

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE)).contains("text/html");
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo("0");
        assertThat(response.content().readableBytes()).isZero();

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldRejectUnsupportedUiMethod() {
        EmbeddedChannel channel = new EmbeddedChannel(new AdminUiHandler());

        channel.writeInbound(request(HttpMethod.POST, "/admin/ui"));

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.METHOD_NOT_ALLOWED);

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldReturn404ForUnknownUiResource() {
        EmbeddedChannel channel = new EmbeddedChannel(new AdminUiHandler());

        channel.writeInbound(request(HttpMethod.GET, "/admin/ui/missing.js"));

        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void shouldPassThroughNonUiRequests() {
        EmbeddedChannel channel = new EmbeddedChannel(new AdminUiHandler());
        FullHttpRequest request = request(HttpMethod.GET, "/health");

        channel.writeInbound(request);

        assertThat((FullHttpResponse) channel.readOutbound()).isNull();
        FullHttpRequest forwarded = channel.readInbound();
        assertThat(forwarded.uri()).isEqualTo("/health");
        forwarded.release();
        channel.finishAndReleaseAll();
    }

    private FullHttpRequest request(HttpMethod method, String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
    }
}
