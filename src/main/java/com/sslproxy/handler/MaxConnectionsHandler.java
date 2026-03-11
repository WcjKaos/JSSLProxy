package com.sslproxy.handler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.metrics.ConnectionTracker;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * 最大连接数限制 Handler
 * <p>
 * 限制同时连接的客户端数量，超过限制时拒绝新连接。
 * 此 Handler 是 Sharable 的，可以在多个 Channel 之间共享。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
@Sharable
public class MaxConnectionsHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(MaxConnectionsHandler.class);

    private static final AtomicBoolean draining = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService drainScheduler;
    private static volatile ScheduledFuture<?> drainTimeoutFuture;

    private final int maxConnections;
    private final AtomicInteger currentConnections = new AtomicInteger(0);

    /**
     * 构造最大连接数限制 Handler
     *
     * @param maxConnections 最大连接数，0 或负数表示不限制
     */
    public MaxConnectionsHandler(int maxConnections) {
        this.maxConnections = maxConnections;
        logger.info("MaxConnectionsHandler initialized: maxConnections={}",
                maxConnections > 0 ? maxConnections : "unlimited");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (draining.get()) {
            logger.info("Drain mode: rejecting new connection from {}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        if (maxConnections > 0) {
            int current = currentConnections.incrementAndGet();
            if (current > maxConnections) {
                currentConnections.decrementAndGet();
                logger.warn("Max connections ({}) exceeded, rejecting connection from: {}",
                        maxConnections, ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
            logger.debug("Connection accepted: current={}, max={}, from={}",
                    current, maxConnections, ctx.channel().remoteAddress());
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (maxConnections > 0) {
            int current = currentConnections.updateAndGet(val -> Math.max(0, val - 1));
            logger.debug("Connection closed: current={}, max={}, from={}",
                    current, maxConnections, ctx.channel().remoteAddress());
        }
        super.channelInactive(ctx);
    }

    /**
     * 获取当前连接数
     *
     * @return 当前连接数
     */
    public int getCurrentConnections() {
        return Math.max(0, currentConnections.get());
    }

    /**
     * 获取最大连接数限制
     *
     * @return 最大连接数，0 表示不限制
     */
    public int getMaxConnections() {
        return maxConnections;
    }

    /**
     * 是否处于 Drain 模式
     */
    public static boolean isDraining() {
        return draining.get();
    }

    /**
     * 启动 Drain 模式
     *
     * @param timeoutSeconds 超时秒数，超时后强制关闭存量连接；0 表示无超时
     */
    public static void startDrain(int timeoutSeconds) {
        startDrain(timeoutSeconds, null);
    }

    public static void startDrain(int timeoutSeconds, ConnectionTracker connectionTracker) {
        draining.set(true);
        logger.info("Drain mode started (timeout={}s)", timeoutSeconds);

        if (timeoutSeconds > 0 && connectionTracker != null) {
            cancelDrainTimeout();
            final ConnectionTracker tracker = connectionTracker;
            drainScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "drain-timeout");
                t.setDaemon(true);
                return t;
            });
            drainTimeoutFuture = drainScheduler.schedule(() -> {
                logger.warn("Drain timeout reached ({}s), force-closing remaining connections", timeoutSeconds);
                tracker.closeAll();
            }, timeoutSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * 取消 Drain 模式
     */
    public static void cancelDrain() {
        draining.set(false);
        cancelDrainTimeout();
        logger.info("Drain mode cancelled");
    }

    private static void cancelDrainTimeout() {
        if (drainTimeoutFuture != null) {
            drainTimeoutFuture.cancel(false);
            drainTimeoutFuture = null;
        }
        if (drainScheduler != null) {
            drainScheduler.shutdownNow();
            drainScheduler = null;
        }
    }
}
