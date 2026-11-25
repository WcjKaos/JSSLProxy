package com.sslproxy.integration;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import java.nio.file.Files;
import java.security.KeyStore;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.CharsetUtil;

/**
 * 测试用后端 HTTP/HTTPS 服务器
 *
 * 用于集成测试，模拟真实后端服务
 */
public class TestBackendServer implements Closeable {

    private final int port;
    private final boolean useSsl;
    private final TestCertificates testCerts;
    private final Path keystorePath;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final AtomicReference<String> lastRequestMethod = new AtomicReference<>();

    // 可配置的响应行为
    private volatile int responseStatus = 200;
    private volatile String responseBody = "OK";
    private volatile int responseDelayMs = 0;
    private volatile boolean shouldFail = false;
    private volatile CompletableFuture<Void> responseGate;
    private volatile int interimResponseStatus;

    /**
     * 创建 HTTP 后端服务器
     */
    public TestBackendServer(int port) {
        this.port = port;
        this.useSsl = false;
        this.testCerts = null;
        this.keystorePath = null;
    }

    /**
     * 创建 HTTPS 后端服务器
     */
    public TestBackendServer(int port, TestCertificates testCerts, Path keystorePath) {
        this.port = port;
        this.useSsl = true;
        this.testCerts = testCerts;
        this.keystorePath = keystorePath;
    }

    /**
     * 启动服务器
     */
    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        SslContext sslContext = null;
        if (useSsl && testCerts != null && keystorePath != null) {
            // 加载密钥库
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(Files.newInputStream(keystorePath), TestCertificates.getDefaultPassword());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, TestCertificates.getDefaultPassword());

            sslContext = SslContextBuilder
                    .forServer(kmf)
                    .build();
        }

        final SslContext finalSslContext = sslContext;

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        if (finalSslContext != null) {
                            pipeline.addLast(finalSslContext.newHandler(ch.alloc()));
                        }

                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new TestBackendHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
    }

    /**
     * 停止服务器
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ========== 配置方法 ==========

    /**
     * 设置响应状态码
     */
    public TestBackendServer withResponseStatus(int status) {
        this.responseStatus = status;
        return this;
    }

    /**
     * 设置响应体
     */
    public TestBackendServer withResponseBody(String body) {
        this.responseBody = body;
        return this;
    }

    /**
     * 设置响应延迟
     */
    public TestBackendServer withResponseDelay(int delayMs) {
        this.responseDelayMs = delayMs;
        return this;
    }

    /**
     * 设置是否模拟失败（关闭连接）
     */
    public TestBackendServer withFailure(boolean shouldFail) {
        this.shouldFail = shouldFail;
        return this;
    }

    public TestBackendServer withResponseGate(CompletableFuture<Void> responseGate) {
        this.responseGate = responseGate;
        return this;
    }

    public TestBackendServer withInterimResponse(int status) {
        this.interimResponseStatus = status;
        return this;
    }

    /**
     * 重置配置
     */
    public void resetConfig() {
        this.responseStatus = 200;
        this.responseBody = "OK";
        this.responseDelayMs = 0;
        this.shouldFail = false;
        this.responseGate = null;
        this.interimResponseStatus = 0;
    }

    // ========== 统计方法 ==========

    /**
     * 获取请求计数
     */
    public int getRequestCount() {
        return requestCount.get();
    }

    /**
     * 获取最后一个请求路径
     */
    public String getLastRequestPath() {
        return lastRequestPath.get();
    }

    /**
     * 获取最后一个请求方法
     */
    public String getLastRequestMethod() {
        return lastRequestMethod.get();
    }

    /**
     * 重置统计
     */
    public void resetStats() {
        requestCount.set(0);
        lastRequestPath.set(null);
        lastRequestMethod.set(null);
    }

    /**
     * 获取端口
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取服务器地址
     */
    public String getAddress() {
        String scheme = useSsl ? "https" : "http";
        return scheme + "://localhost:" + port;
    }

    // ========== 内部 Handler ==========

    private class TestBackendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private CompletableFuture<Void> responseTail = CompletableFuture.completedFuture(null);

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            // 记录请求
            requestCount.incrementAndGet();
            lastRequestPath.set(request.uri());
            lastRequestMethod.set(request.method().name());

            // 模拟失败
            if (shouldFail) {
                ctx.close();
                return;
            }

            if (interimResponseStatus > 0) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(interimResponseStatus)
                ));
            }

            // 构建响应
            String responseContent = buildResponseContent(request);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(responseStatus),
                    Unpooled.copiedBuffer(responseContent, CharsetUtil.UTF_8)
            );

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set("X-Backend-Server", "TestBackendServer");
            response.headers().set("X-Request-Id", request.headers().get("X-Request-Id", "none"));

            // 保持连接
            boolean keepAlive = HttpUtil.isKeepAlive(request);
            if (keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            int delayMs = responseDelayMs;
            CompletableFuture<Void> previousResponse = responseTail;
            CompletableFuture<Void> responseSent = new CompletableFuture<>();
            responseTail = responseSent;
            AtomicBoolean pending = new AtomicBoolean(true);
            ChannelFutureListener releaseOnClose = future -> {
                if (pending.compareAndSet(true, false)) {
                    response.release();
                    responseSent.complete(null);
                }
            };
            ctx.channel().closeFuture().addListener(releaseOnClose);
            Runnable writeResponse = () -> {
                if (!pending.compareAndSet(true, false)) {
                    return;
                }
                ctx.channel().closeFuture().removeListener(releaseOnClose);
                if (!ctx.channel().isActive()) {
                    response.release();
                    responseSent.complete(null);
                    return;
                }
                ctx.writeAndFlush(response).addListener(future -> {
                    if (!keepAlive) {
                        ctx.close();
                    }
                    responseSent.complete(null);
                });
            };
            Runnable scheduleResponse = () -> {
                if (!pending.get()) {
                    return;
                }
                if (delayMs > 0) {
                    ctx.executor().schedule(writeResponse, delayMs, TimeUnit.MILLISECONDS);
                } else {
                    writeResponse.run();
                }
            };
            CompletableFuture<Void> gate = responseGate;
            CompletableFuture<Void> ready = gate == null
                    ? previousResponse
                    : previousResponse.thenCompose(ignored -> gate);
            ready.whenComplete((ignored, failure) -> {
                if (pending.get()) {
                    ctx.executor().execute(scheduleResponse);
                }
            });
        }

        private String buildResponseContent(FullHttpRequest request) {
            StringBuilder sb = new StringBuilder();
            sb.append(responseBody);
            sb.append("\n");
            sb.append("Method: ").append(request.method().name()).append("\n");
            sb.append("Path: ").append(request.uri()).append("\n");
            sb.append("Request-Count: ").append(requestCount.get()).append("\n");
            return sb.toString();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
