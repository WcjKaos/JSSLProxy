package com.sslproxy.management.handler;

import com.sslproxy.metrics.MetricsRegistry;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Prometheus 指标处理器
 * <p>
 * 处理 /metrics 端点，返回 Prometheus 格式的指标数据。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class MetricsHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger logger = LoggerFactory.getLogger(MetricsHandler.class);

    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final MetricsRegistry metricsRegistry;

    public MetricsHandler(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();

        if ("/metrics".equals(uri)) {
            handleMetrics(ctx);
        } else {
            // 传递给下一个处理器
            ctx.fireChannelRead(request.retain());
        }
    }

    /**
     * 处理指标请求
     */
    private void handleMetrics(ChannelHandlerContext ctx) {
        String content;
        if (metricsRegistry != null && metricsRegistry.isEnabled()) {
            content = metricsRegistry.scrape();
        } else {
            content = "# Metrics disabled\n";
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.wrappedBuffer(bytes)
        );

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, PROMETHEUS_CONTENT_TYPE);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        logger.debug("Metrics endpoint accessed, {} bytes returned", bytes.length);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error in metrics handler: {}", cause.getMessage());
        ctx.close();
    }
}
