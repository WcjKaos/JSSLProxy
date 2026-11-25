package com.sslproxy.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLException;

import com.sslproxy.client.BackendConnectionPool;
import com.sslproxy.client.BackendConnector;
import com.sslproxy.config.ProxyConfig;
import com.sslproxy.loadbalance.LoadBalancer.Backend;
import com.sslproxy.loadbalance.LoadBalancerManager;
import com.sslproxy.metrics.ConnectionTracker;
import com.sslproxy.metrics.ProxyMetrics;
import com.sslproxy.resilience.BackendCircuitBreaker;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * 代理前端处理器
 * <p>
 * 处理客户端请求，建立到后端服务器的连接，并进行双向数据转发。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ProxyFrontendHandler extends SimpleChannelInboundHandler<HttpObject> {
    private static final Logger logger = LoggerFactory.getLogger(ProxyFrontendHandler.class);

    private volatile Channel outboundChannel;
    private final BackendConnector backendConnector;
    private final BackendConnectionPool connectionPool;
    private final boolean useConnectionPool;
    private final ProxyConfig config;
    private final ProxyMetrics metrics;
    private final ConnectionTracker connectionTracker;
    private final BackendCircuitBreaker circuitBreaker;
    private final LoadBalancerManager loadBalancerManager;
    private Backend currentBackend;
    private final Deque<BackendCall> pendingCalls = new ArrayDeque<>();
    private final Deque<BackendConnectionListener> connectionListeners = new ArrayDeque<>();
    private boolean connecting;

    private String requestUri;
    private String clientAddress;

    /**
     * 构造代理前端处理器（完整依赖注入）
     *
     * @param config              代理配置
     * @param connectionPool      后端连接池（可为 null）
     * @param metrics             代理指标
     * @param connectionTracker   连接追踪器
     * @param circuitBreaker      熔断器（可为 null）
     * @param loadBalancerManager 负载均衡管理器（可为 null）
     */
    public ProxyFrontendHandler(ProxyConfig config, BackendConnectionPool connectionPool,
                                ProxyMetrics metrics, ConnectionTracker connectionTracker,
                                BackendCircuitBreaker circuitBreaker,
                                LoadBalancerManager loadBalancerManager) {
        this.config = config;
        this.connectionPool = connectionPool;
        this.metrics = metrics;
        this.connectionTracker = connectionTracker;
        this.circuitBreaker = circuitBreaker;
        this.loadBalancerManager = loadBalancerManager;
        this.useConnectionPool = connectionPool != null
                && config.performance != null
                && config.performance.backendPool != null
                && config.performance.backendPool.enabled;
        this.backendConnector = useConnectionPool ? null : new BackendConnector(config, loadBalancerManager);
    }

    /**
     * 构造代理前端处理器（不使用连接池，向后兼容）
     *
     * @param config 代理配置
     */
    public ProxyFrontendHandler(ProxyConfig config) {
        this(config, null, ProxyMetrics.noop(), new ConnectionTracker(ProxyMetrics.noop()), null, null);
    }

    /**
     * Channel 激活时调用
     * 
     * @param ctx Channel 上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        clientAddress = ctx.channel().remoteAddress().toString();
        logger.debug("Client channel active: {}", clientAddress);
        ctx.read();
    }

    /**
     * 处理 HTTP 请求/响应对象
     * 
     * @param ctx Channel 上下文
     * @param msg HTTP 消息对象
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
        if (msg instanceof FullHttpRequest fullRequest) {
            handleFullHttpRequest(ctx, fullRequest);
        } else if (msg instanceof HttpRequest request) {
            handleHttpRequest(ctx, request);
        } else if (msg instanceof HttpContent content) {
            forwardToBackend(content);
        }
    }

    /**
     * 处理完整的 HTTP 请求
     *
     * @param ctx     Channel 上下文
     * @param request 完整的 HTTP 请求
     */
    private void handleFullHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        requestUri = request.uri();

        logger.debug("Received request: {} {} from {}", request.method(), requestUri, clientAddress);

        // 检查熔断器状态
        if (isCircuitBreakerOpen(request.method().toString())) {
            sendServiceUnavailableResponse(ctx, "Service temporarily unavailable (circuit breaker open)");
            return;
        }
        BackendCall call = beginBackendCall(ctx);

        // 建立到后端服务器的连接（异步）
        if (outboundChannel == null || !outboundChannel.isActive()) {
            // 在异步回调中会使用 request，所以先 retain() 保持引用
            request.retain();

            establishBackendConnection(ctx,
                    channel -> doForwardRequest(ctx, request, call),
                    cause -> {
                        completeBackendCall(call, false);
                        request.release();
                        sendErrorResponse(ctx, "Failed to connect to backend: " +
                                (cause != null ? cause.getMessage() : "Unknown error"));
                    });
        } else {
            // 已有连接，直接转发（需要 retain）
            request.retain();
            doForwardRequest(ctx, request, call);
        }
    }

    /**
     * 实际执行转发请求到后端服务器
     * <p>
     * 注意：调用此方法前必须已经 retain() 过 request
     * </p>
     */
    private void doForwardRequest(ChannelHandlerContext ctx, FullHttpRequest request, BackendCall call) {
        if (outboundChannel != null && outboundChannel.isActive()) {
            // 修改请求头（如果需要）
            modifyRequestHeaders(request);

            // writeAndFlush 会消费 request 的引用计数
            outboundChannel.writeAndFlush(request).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    logger.error("Failed to forward request to backend: {}",
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    ctx.executor().execute(() -> {
                        if (call.completed || future.channel() != outboundChannel) {
                            return;
                        }
                        completeBackendCall(call, false);
                        failPendingCalls();
                        closeBackendChannel(true);
                        ctx.close();
                    });
                }
            });
        } else {
            completeBackendCall(call, false);
            request.release(); // 没有可用连接，释放引用
            sendErrorResponse(ctx, "Backend connection not available");
        }
    }

    /**
     * 处理流式 HTTP 请求（仅请求头部分）
     * <p>
     * 用于处理分块传输的 HTTP 请求，请求头和请求体分开到达。
     * 此方法处理请求头部分，后续的 HttpContent 会通过 forwardToBackend 转发。
     * </p>
     *
     * @param ctx Channel 上下文
     * @param request HTTP 请求
     */
    private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest request) {
        requestUri = request.uri();

        logger.debug("Received streaming request: {} {} from {}", request.method(), requestUri, clientAddress);

        // 检查熔断器状态
        if (isCircuitBreakerOpen(request.method().toString())) {
            sendServiceUnavailableResponse(ctx, "Service temporarily unavailable (circuit breaker open)");
            return;
        }
        BackendCall call = beginBackendCall(ctx);

        // 建立到后端服务器的连接（异步）
        if (outboundChannel == null || !outboundChannel.isActive()) {
            // HttpRequest 不需要 retain()（它不是 ReferenceCounted）

            establishBackendConnection(ctx,
                    channel -> forwardStreamingRequestToBackend(ctx, request, call),
                    cause -> {
                        completeBackendCall(call, false);
                        sendErrorResponse(ctx, "Failed to connect to backend: " +
                                (cause != null ? cause.getMessage() : "Unknown error"));
                    });
        } else {
            // 已有连接，直接转发
            forwardStreamingRequestToBackend(ctx, request, call);
        }
    }

    /**
     * 检查熔断器是否已打开
     *
     * @param method HTTP 方法（用于日志）
     * @return true 如果熔断器已打开，请求应被拒绝
     */
    private boolean isCircuitBreakerOpen(String method) {
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            logger.warn("Circuit breaker OPEN, rejecting request: {} {} from {}",
                    method, requestUri, clientAddress);
            metrics.incrementConnectionErrors("circuit_open");
            return true;
        }
        return false;
    }

    /**
     * 建立到后端服务器的连接（连接池或直连）
     * <p>
     * 统一的后端连接建立逻辑，避免在 handleFullHttpRequest 和 handleHttpRequest 中重复。
     * </p>
     *
     * @param ctx       Channel 上下文
     * @param onSuccess 连接成功回调
     * @param onFailure 连接失败回调
     */
    private void establishBackendConnection(ChannelHandlerContext ctx,
                                            Consumer<Channel> onSuccess,
                                            Consumer<Throwable> onFailure) {
        connectionListeners.addLast(new BackendConnectionListener(onSuccess, onFailure));
        if (connecting) {
            return;
        }
        connecting = true;
        try {
            BackendTarget target = resolveBackend();
            if (useConnectionPool) {
                Future<Channel> acquireFuture = connectionPool.acquire(
                        ctx.channel(), target.host, target.port, target.protocol, target.backend);
                acquireFuture.addListener(future -> dispatchBackendConnection(
                        ctx, (Channel) future.getNow(), target.backend, future.cause()));
            } else {
                backendConnector.connectToBackend(
                        ctx.channel(), target.host, target.port, target.protocol, target.backend)
                        .addListener((ChannelFutureListener) future -> dispatchBackendConnection(ctx,
                                future.isSuccess() ? future.channel() : null, target.backend, future.cause()));
            }
        } catch (Exception cause) {
            finishBackendConnection(ctx, null, null, cause);
        }
    }

    private void dispatchBackendConnection(ChannelHandlerContext ctx, Channel channel, Backend backend, Throwable cause) {
        try {
            ctx.executor().execute(() -> finishBackendConnection(ctx, channel, backend, cause));
        } catch (RejectedExecutionException rejected) {
            if (channel != null) {
                channel.close();
                if (useConnectionPool) {
                    connectionPool.release(channel, backend);
                }
            }
        }
    }

    private void finishBackendConnection(ChannelHandlerContext ctx, Channel channel, Backend backend, Throwable cause) {
        connecting = false;
        if (channel != null && !ctx.channel().isActive()) {
            channel.close();
            if (useConnectionPool) {
                connectionPool.release(channel, backend);
            }
            channel = null;
            cause = new ClosedChannelException();
        }
        if (channel == null) {
            metrics.incrementConnectionErrors(classifyConnectionError(cause));
            while (!connectionListeners.isEmpty()) {
                connectionListeners.removeFirst().onFailure().accept(cause);
            }
            return;
        }
        outboundChannel = channel;
        currentBackend = backend;
        while (!connectionListeners.isEmpty()) {
            connectionListeners.removeFirst().onSuccess().accept(channel);
        }
    }

    /**
     * 转发流式请求头到后端服务器
     */
    private void forwardStreamingRequestToBackend(ChannelHandlerContext ctx, HttpRequest request, BackendCall call) {
        if (outboundChannel != null && outboundChannel.isActive()) {
            // 修改请求头
            modifyRequestHeaders(request);

            outboundChannel.writeAndFlush(request).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    logger.error("Failed to forward streaming request header to backend: {}",
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    // 关闭后端连接和前端连接
                    ctx.executor().execute(() -> {
                        if (call.completed || future.channel() != outboundChannel) {
                            return;
                        }
                        completeBackendCall(call, false);
                        failPendingCalls();
                        closeBackendChannel(true);
                        ctx.close();
                    });
                }
            });
        } else {
            completeBackendCall(call, false);
            sendErrorResponse(ctx, "Backend connection not available");
        }
    }

    /**
     * 转发数据到后端服务器
     * 
     * @param msg 要转发的消息
     */
    private void forwardToBackend(Object msg) {
        if (outboundChannel != null && outboundChannel.isActive()) {
            // retain() 防止 SimpleChannelInboundHandler 的 auto-release 在写操作完成前释放 ByteBuf
            io.netty.util.ReferenceCountUtil.retain(msg);
            outboundChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    logger.error("Failed to forward data to backend: {}",
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    future.channel().close();
                }
            });
        } else {
            logger.warn("Backend channel not available, dropped forwarded data");
        }
    }

    /**
     * 修改请求头（例如添加 X-Forwarded-For）
     * <p>
     * W3C Trace Context: traceparent / tracestate 等 header 由代理透明转发，
     * 不创建、不修改、不删除。代理不是 span 起点。
     * </p>
     *
     * @param request HTTP 请求
     */
    void modifyRequestHeaders(HttpRequest request) {
        // 添加代理相关的请求头；其余 header（含 traceparent/tracestate）原样透传
        HttpHeaders headers = request.headers();
        
        // X-Forwarded-For
        String clientIp = extractClientIp(clientAddress);
        String existingXFF = headers.get("X-Forwarded-For");
        if (existingXFF != null) {
            headers.set("X-Forwarded-For", existingXFF + ", " + clientIp);
        } else {
            headers.set("X-Forwarded-For", clientIp);
        }
        
        // X-Forwarded-Proto
        headers.set("X-Forwarded-Proto", "https");
        
        // X-Forwarded-Host
        String host = headers.get("Host");
        if (host != null) {
            headers.set("X-Forwarded-Host", host);
        }
    }

    /**
     * 从地址字符串提取 IP
     * 
     * @param address 地址字符串
     * @return IP 地址
     */
    private String extractClientIp(String address) {
        if (address == null) {
            return "unknown";
        }
        if (address.startsWith("/")) {
            address = address.substring(1);
        }
        // IPv6 地址格式: [::1]:8080
        int bracketClose = address.lastIndexOf(']');
        if (bracketClose >= 0) {
            // IPv6 bracketed: 去掉括号，返回 IP 部分
            int bracketOpen = address.indexOf('[');
            if (bracketOpen >= 0) {
                return address.substring(bracketOpen + 1, bracketClose);
            }
            return address.substring(0, bracketClose + 1);
        }
        // IPv4 地址格式: 127.0.0.1:8080
        int colonIndex = address.lastIndexOf(':');
        if (colonIndex > 0) {
            return address.substring(0, colonIndex);
        }
        return address;
    }

    /**
     * 发送错误响应
     *
     * @param ctx     Channel 上下文
     * @param message 内部错误消息（仅日志，不暴露给客户端）
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, String message) {
        sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "Bad Gateway", message);
    }

    /**
     * 发送服务不可用响应（熔断时使用）
     *
     * @param ctx     Channel 上下文
     * @param message 内部错误消息（仅日志，不暴露给客户端）
     */
    private void sendServiceUnavailableResponse(ChannelHandlerContext ctx, String message) {
        sendErrorResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "Service Temporarily Unavailable", message);
    }

    /**
     * 发送错误 HTTP 响应的通用方法
     *
     * @param ctx           Channel 上下文
     * @param status        HTTP 状态码
     * @param clientMessage 返回给客户端的消息
     * @param logMessage    内部日志消息
     */
    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status,
                                   String clientMessage, String logMessage) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(clientMessage.getBytes())
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        if (status == HttpResponseStatus.SERVICE_UNAVAILABLE) {
            response.headers().set("Retry-After", "30");
        }

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);

        logger.warn("Sent error response {} to {}: {}", status.code(), clientAddress, logMessage);
    }

    /**
     * 记录熔断器成功调用
     */
    private BackendCall beginBackendCall(ChannelHandlerContext ctx) {
        BackendCall call = new BackendCall();
        pendingCalls.addLast(call);
        int timeout = config.connection != null && config.connection.timeout > 0
                ? config.connection.timeout : 30000;
        call.timeout = ctx.executor().schedule(() -> {
            if (!call.completed) {
                failPendingCalls();
                closeBackendChannel(true);
                sendErrorResponse(ctx, HttpResponseStatus.GATEWAY_TIMEOUT,
                        "Gateway Timeout", "Backend response deadline exceeded");
            }
        }, timeout, TimeUnit.MILLISECONDS);
        return call;
    }

    private void completeBackendCall(BackendCall call, boolean success) {
        if (call.completed) {
            return;
        }
        call.completed = true;
        call.timeout.cancel(false);
        pendingCalls.remove(call);
        if (circuitBreaker != null) {
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - call.startedNanos);
            if (success) {
                circuitBreaker.recordSuccess(duration);
            } else {
                circuitBreaker.recordFailure(duration);
            }
        }
    }

    void backendResponse(ChannelHandlerContext ctx, Channel channel, int status, boolean complete, boolean failed) {
        if (channel != outboundChannel || pendingCalls.isEmpty()) {
            return;
        }
        if (failed) {
            failPendingCalls();
            closeBackendChannel(true);
            sendErrorResponse(ctx, "Invalid backend response");
            return;
        }
        if (status > 0 && status < 200 && status != 101) {
            return;
        }
        BackendCall call = pendingCalls.peekFirst();
        if (status >= 200 || status == 101) {
            call.status = status;
        }
        if (complete && call.status > 0) {
            completeBackendCall(call, call.status < 500);
        }
    }

    void backendFailure(ChannelHandlerContext ctx, Channel channel) {
        if (channel == outboundChannel) {
            boolean incomplete = !pendingCalls.isEmpty();
            failPendingCalls();
            closeBackendChannel(true);
            if (incomplete) {
                sendErrorResponse(ctx, "Backend closed before completing its response");
            } else {
                flushAndClose(ctx);
            }
        }
    }

    private void failPendingCalls() {
        while (!pendingCalls.isEmpty()) {
            completeBackendCall(pendingCalls.peekFirst(), false);
        }
    }

    private void cancelPendingCalls() {
        while (!pendingCalls.isEmpty()) {
            BackendCall call = pendingCalls.removeFirst();
            call.completed = true;
            call.timeout.cancel(false);
            if (circuitBreaker != null) {
                circuitBreaker.releasePermission();
            }
        }
    }

    private static final class BackendCall {
        private final long startedNanos = System.nanoTime();
        private int status;
        private boolean completed;
        private ScheduledFuture<?> timeout;
    }

    private record BackendConnectionListener(Consumer<Channel> onSuccess, Consumer<Throwable> onFailure) {
    }

    /**
     * Channel 不活跃时调用
     *
     * @param ctx Channel 上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("Client channel inactive: {}", clientAddress);
        // 从连接追踪器注销
        connectionTracker.unregister(ctx.channel());
        boolean incomplete = !pendingCalls.isEmpty();
        cancelPendingCalls();
        closeBackendChannel(incomplete);
        while (!connectionListeners.isEmpty()) {
            connectionListeners.removeFirst().onFailure().accept(new ClosedChannelException());
        }
    }

    /**
     * 用户事件触发
     *
     * @param ctx Channel 上下文
     * @param evt 事件
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.ALL_IDLE) {
                logger.info("Connection idle timeout: {}", clientAddress);
                failPendingCalls();
                closeBackendChannel(true);
                flushAndClose(ctx);
            }
        }
    }

    /**
     * 可写性变化处理 - 实现背压机制
     * <p>
     * 当前端 Channel 变得不可写时，暂停读取后端数据；
     * 当变得可写时，恢复读取。防止快速后端导致内存积压。
     * </p>
     *
     * @param ctx Channel 上下文
     */
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (outboundChannel != null) {
            // 根据前端 Channel 的可写状态，控制后端 Channel 的自动读取
            boolean isWritable = ctx.channel().isWritable();
            outboundChannel.config().setAutoRead(isWritable);
            if (!isWritable) {
                logger.debug("Frontend channel not writable, pausing backend reads for {}", clientAddress);
            } else {
                logger.debug("Frontend channel writable again, resuming backend reads for {}", clientAddress);
            }
        }
        super.channelWritabilityChanged(ctx);
    }

    /**
     * 异常处理
     * 
     * @param ctx Channel 上下文
     * @param cause 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception in proxy frontend handler for {}: {}",
                clientAddress, cause.getMessage(), cause);
        boolean incomplete = !pendingCalls.isEmpty();
        cancelPendingCalls();
        closeBackendChannel(incomplete);
        flushAndClose(ctx);
    }

    /**
     * 刷新待发送数据并关闭连接
     * <p>
     * 确保所有待发送的数据都被发送后再关闭连接，避免数据丢失。
     * </p>
     *
     * @param ctx Channel 上下文
     */
    private void flushAndClose(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.close();
        }
    }

    private BackendTarget resolveBackend() {
        LoadBalancerManager lbManager = loadBalancerManager;
        if (lbManager != null) {
            Backend backend = lbManager.selectBackend();
            if (backend != null) {
                return new BackendTarget(backend.getHost(), backend.getPort(), backend.getProtocol(), backend);
            }
            logger.warn("No healthy backends available, falling back to default target");
        }
        return new BackendTarget(config.target.host, config.target.port, config.target.protocol, null);
    }

    private static final class BackendTarget {
        final String host;
        final int port;
        final String protocol;
        final Backend backend;

        BackendTarget(String host, int port, String protocol, Backend backend) {
            this.host = host;
            this.port = port;
            this.protocol = protocol != null ? protocol : "http";
            this.backend = backend;
        }
    }

    /**
     * 将后端连接失败原因映射为分类标签
     * <p>Package-private for unit testing.</p>
     *
     * @param cause 连接失败异常
     * @return 分类标签：timeout / refused / tls / dns / pool_exhausted / circuit_open / other
     */
    static String classifyConnectionError(Throwable cause) {
        if (cause == null) return "other";
        if (cause instanceof ConnectTimeoutException) return "timeout";
        if (cause instanceof SSLException) return "tls";
        if (cause instanceof UnknownHostException) return "dns";
        if (cause instanceof ConnectException) {
            String msg = cause.getMessage();
            if (msg != null && msg.toLowerCase().contains("refused")) return "refused";
        }
        String msg = cause.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("pool") && (lower.contains("exhausted") || lower.contains("timeout")
                    || lower.contains("acquire"))) {
                return "pool_exhausted";
            }
            if (lower.contains("connection refused")) return "refused";
            if (lower.contains("timed out") || lower.contains("connect timeout")) return "timeout";
        }
        return "other";
    }

    /**
     * 关闭或释放后端连接
     */
    private void closeBackendChannel(boolean incomplete) {
        if (outboundChannel != null) {
            if (incomplete) {
                outboundChannel.close();
            }
            if (useConnectionPool && connectionPool != null) {
                // 使用连接池时，释放连接回池中
                logger.debug("Releasing backend connection to pool");
                connectionPool.release(outboundChannel, currentBackend);
            } else {
                // 不使用连接池时，直接关闭连接
                logger.debug("Closing backend channel");
                outboundChannel.close();
            }
            outboundChannel = null;
            currentBackend = null;
        }
    }
}
