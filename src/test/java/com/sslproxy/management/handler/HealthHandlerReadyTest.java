package com.sslproxy.management.handler;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.SslContextHolder;
import com.sslproxy.handler.MaxConnectionsHandler;
import com.sslproxy.loadbalance.LoadBalancerManager;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.server.TransportFactory;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link HealthHandler} /ready endpoint.
 *
 * <p>Uses {@link EmbeddedChannel} with {@link HealthHandler} to verify
 * readiness probe behaviour under different conditions. The pipeline omits
 * {@code HttpServerCodec} so that outbound {@link FullHttpResponse} objects
 * are available directly (the codec would otherwise encode them into raw
 * {@code ByteBuf}).</p>
 */
class HealthHandlerReadyTest {

    private static final Gson gson = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    @AfterEach
    void resetDrainState() {
        MaxConnectionsHandler.cancelDrain();
    }

    // ------------------------------------------------------------------ helpers

    private EmbeddedChannel createChannel() {
        ProxyConfig config = new ProxyConfig();
        config.target = new ProxyConfig.Target();
        config.target.host = "localhost";
        config.target.port = 8080;
        config.target.protocol = "http";

        return new EmbeddedChannel(
                new HealthHandler(config, null,
                        com.sslproxy.metrics.ProxyMetrics.noop(), null, null)
        );
    }

    private FullHttpResponse sendReadyRequest(EmbeddedChannel channel) {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/ready");
        channel.writeInbound(request);
        return channel.readOutbound();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(FullHttpResponse response) {
        String json = response.content().toString(StandardCharsets.UTF_8);
        return gson.fromJson(json, MAP_TYPE);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void should_return503WithDrainingStatus_when_drainModeIsActive() {
        MaxConnectionsHandler.startDrain(0);

        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendReadyRequest(channel);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);

        Map<String, Object> body = parseBody(response);
        assertThat(body.get("status")).isEqualTo("DRAINING");

        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) body.get("checks");
        assertThat(checks).isNotNull();
        assertThat(checks.get("drain")).isEqualTo("ACTIVE");

        assertThat(body).containsKey("timestamp");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_return503WithDownStatus_when_notDrainingButSslContextHolderIsNull() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendReadyRequest(channel);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);

        Map<String, Object> body = parseBody(response);
        assertThat(body.get("status")).isEqualTo("DOWN");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_containChecksWithExpectedKeys_when_notDraining() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendReadyRequest(channel);

        assertThat(response).isNotNull();

        Map<String, Object> body = parseBody(response);

        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) body.get("checks");
        assertThat(checks).isNotNull();
        assertThat(checks).containsKeys("backendTarget", "sslContext", "metrics");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_showBackendTargetAsUnknown_when_loadBalancerManagerNotInitialized() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendReadyRequest(channel);

        assertThat(response).isNotNull();

        Map<String, Object> body = parseBody(response);

        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) body.get("checks");

        @SuppressWarnings("unchecked")
        Map<String, Object> backendTarget = (Map<String, Object>) checks.get("backendTarget");
        assertThat(backendTarget).isNotNull();
        assertThat(backendTarget.get("host")).isEqualTo("localhost");
        assertThat(((Number) backendTarget.get("port")).intValue()).isEqualTo(8080);
        assertThat(backendTarget.get("status")).isEqualTo("UNKNOWN");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_showSslContextAsDown_when_sslContextHolderIsNull() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendReadyRequest(channel);

        assertThat(response).isNotNull();

        Map<String, Object> body = parseBody(response);

        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) body.get("checks");

        @SuppressWarnings("unchecked")
        Map<String, Object> sslContext = (Map<String, Object>) checks.get("sslContext");
        assertThat(sslContext).isNotNull();
        assertThat(sslContext.get("status")).isEqualTo("DOWN");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_returnJsonContentType_when_readyEndpointIsCalled() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendReadyRequest(channel);

        assertThat(response).isNotNull();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_TYPE))
                .isEqualTo("application/json; charset=UTF-8");

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_containTimestamp_when_readyEndpointIsCalled() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendReadyRequest(channel);

        assertThat(response).isNotNull();

        Map<String, Object> body = parseBody(response);
        assertThat(body).containsKey("timestamp");
        assertThat(((Number) body.get("timestamp")).longValue()).isPositive();

        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void should_return503_when_noBackendsAreConfigured() {
        assertUnverifiedBackendsAreNotReady(false);
    }

    @Test
    void should_return503_when_firstPeriodicHealthCheckHasNotCompleted() {
        assertUnverifiedBackendsAreNotReady(true);
    }

    private void assertUnverifiedBackendsAreNotReady(boolean pendingHealthCheck) {
        ProxyConfig config = new ProxyConfig();
        config.target = new ProxyConfig.Target();
        config.target.host = "localhost";
        config.target.port = 8080;
        config.target.protocol = "http";
        config.target.healthCheck = new ProxyConfig.HealthCheckConfig();
        config.target.healthCheck.enabled = pendingHealthCheck;
        EventLoopGroup eventLoops = TransportFactory.createEventLoopGroup(1);
        LoadBalancerManager manager = new LoadBalancerManager(config.target, eventLoops);
        SslContextHolder sslContext = mock(SslContextHolder.class);
        when(sslContext.getCertificateStatus()).thenReturn("UP");
        if (!pendingHealthCheck) {
            manager.getLoadBalancer().removeBackend("localhost", 8080);
        }
        EmbeddedChannel channel = new EmbeddedChannel(
                new HealthHandler(config, null, ProxyMetrics.noop(), manager, sslContext));
        try {
            FullHttpResponse response = sendReadyRequest(channel);
            assertThat(response).isNotNull();
            try {
                assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
                assertThat(parseBody(response).get("status")).isEqualTo("DOWN");
            } finally {
                response.release();
            }
        } finally {
            channel.finishAndReleaseAll();
            manager.stop();
            eventLoops.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
