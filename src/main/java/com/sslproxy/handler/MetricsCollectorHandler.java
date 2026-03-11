package com.sslproxy.handler;

import com.sslproxy.metrics.ProxyMetrics;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.AttributeKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 指标收集处理器
 * <p>
 * 在 Pipeline 中收集请求/响应指标：
 * - 请求计数
 * - 请求耗时
 * - 字节传输量
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class MetricsCollectorHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(MetricsCollectorHandler.class);

    private static final AttributeKey<Timer.Sample> REQUEST_TIMER_KEY =
            AttributeKey.valueOf("requestTimer");

    private final ProxyMetrics metrics;

    public MetricsCollectorHandler(ProxyMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof HttpRequest) {
            // 记录请求开始
            metrics.incrementRequests();
            Timer.Sample sample = metrics.startRequestTimer();
            ctx.channel().attr(REQUEST_TIMER_KEY).set(sample);
        }

        if (msg instanceof HttpContent content) {
            // 记录接收的字节数
            metrics.addBytesReceived(content.content().readableBytes());
        }

        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        if (msg instanceof HttpResponse) {
            // 停止请求计时器
            Timer.Sample sample = ctx.channel().attr(REQUEST_TIMER_KEY).getAndSet(null);
            if (sample != null) {
                metrics.stopRequestTimer(sample);
            }
        }

        if (msg instanceof HttpContent content) {
            // 记录发送的字节数
            metrics.addBytesSent(content.content().readableBytes());
        }

        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        metrics.incrementRequestErrors();

        super.exceptionCaught(ctx, cause);
    }
}
