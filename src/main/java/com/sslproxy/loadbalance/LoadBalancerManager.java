package com.sslproxy.loadbalance;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.ProxyConfig.BackendConfig;
import com.sslproxy.config.ProxyConfig.HealthCheckConfig;
import com.sslproxy.config.SSLContextFactory;
import com.sslproxy.loadbalance.LoadBalancer.Backend;
import com.sslproxy.loadbalance.LoadBalancer.Strategy;

import com.sslproxy.server.TransportFactory;
import com.sslproxy.util.TlsUtil;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultNameResolver;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

/**
 * 负载均衡器管理器
 * <p>
 * 负责初始化负载均衡器、管理后端健康检查。
 * 支持单例模式和多后端配置。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class LoadBalancerManager {
    private static final Logger logger = LoggerFactory.getLogger(LoadBalancerManager.class);

    private final LoadBalancer loadBalancer;
    private final HealthCheckConfig healthCheckConfig;
    private final EventLoopGroup eventLoopGroup;
    private final SslContext healthCheckSslContext;
    private final DefaultEventExecutor resolverExecutor;
    private final AddressResolverGroup<InetSocketAddress> resolverGroup;
    private final Set<Backend> verifiedBackends = ConcurrentHashMap.newKeySet();
    private final Set<Backend> inFlightHealthChecks = ConcurrentHashMap.newKeySet();
    private final Set<BackendProbe> activeProbes = ConcurrentHashMap.newKeySet();
    private final AtomicReference<CompletableFuture<Map<String, Boolean>>> readinessCheck =
            new AtomicReference<>();
    private ScheduledExecutorService healthCheckExecutor;
    private volatile boolean running;
    private final AtomicBoolean stopped = new AtomicBoolean();

    // 健康检查计数器（用于连续成功/失败计数）
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> successCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> failureCounts = new java.util.concurrent.ConcurrentHashMap<>();

    public LoadBalancerManager(ProxyConfig.Target targetConfig, EventLoopGroup eventLoopGroup) {
        this(targetConfig, eventLoopGroup, null);
    }

    public LoadBalancerManager(ProxyConfig.Target targetConfig, EventLoopGroup eventLoopGroup,
                               ProxyConfig.Ssl sslConfig) {
        this.eventLoopGroup = eventLoopGroup;

        // 解析负载均衡策略
        Strategy strategy = parseStrategy(targetConfig.strategy);
        this.loadBalancer = new LoadBalancer(strategy);

        // 添加后端
        if (targetConfig.isMultiBackendMode()) {
            for (BackendConfig backendConfig : targetConfig.backends) {
                Backend backend = new Backend(
                        backendConfig.host,
                        backendConfig.port,
                        backendConfig.protocol != null ? backendConfig.protocol : "http",
                        backendConfig.weight
                );
                loadBalancer.addBackend(backend);
            }
        } else {
            // 单后端模式
            Backend backend = new Backend(
                    targetConfig.host,
                    targetConfig.port,
                    targetConfig.protocol != null ? targetConfig.protocol : "http",
                    1
            );
            loadBalancer.addBackend(backend);
        }

        this.healthCheckConfig = targetConfig.healthCheck;
        this.healthCheckSslContext = createHealthCheckSslContext(sslConfig);
        this.resolverExecutor = new DefaultEventExecutor(task -> {
            Thread thread = new Thread(task, "backend-health-resolver");
            thread.setDaemon(true);
            return thread;
        });
        this.resolverGroup = new AddressResolverGroup<>() {
            @Override
            protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new DefaultNameResolver(resolverExecutor) {
                    @Override
                    protected void doResolve(String hostname, Promise<InetAddress> promise) {
                        resolverExecutor.execute(() -> {
                            try {
                                super.doResolve(hostname, promise);
                            } catch (Exception error) {
                                promise.tryFailure(error);
                            }
                        });
                    }

                    @Override
                    protected void doResolveAll(String hostname, Promise<List<InetAddress>> promise) {
                        resolverExecutor.execute(() -> {
                            try {
                                super.doResolveAll(hostname, promise);
                            } catch (Exception error) {
                                promise.tryFailure(error);
                            }
                        });
                    }
                }.asAddressResolver();
            }
        };

        logger.info("LoadBalancerManager initialized with {} backends, strategy: {}",
                loadBalancer.getBackends().size(), strategy);
    }

    private SslContext createHealthCheckSslContext(ProxyConfig.Ssl sslConfig) {
        if (!isHealthCheckEnabled()
                || loadBalancer.getBackends().stream()
                        .noneMatch(backend -> "https".equalsIgnoreCase(backend.getProtocol()))) {
            return null;
        }
        try {
            return SSLContextFactory.createClientSslContext(sslConfig);
        } catch (Exception error) {
            throw new IllegalArgumentException("Failed to initialize HTTPS health checks", error);
        }
    }

    private boolean isHealthCheckEnabled() {
        return healthCheckConfig != null && healthCheckConfig.enabled;
    }

    /**
     * 解析负载均衡策略
     */
    private Strategy parseStrategy(String strategyName) {
        if (strategyName == null || strategyName.isEmpty()) {
            return Strategy.ROUND_ROBIN;
        }
        try {
            return Strategy.valueOf(strategyName.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown load balancing strategy: {}, using ROUND_ROBIN", strategyName);
            return Strategy.ROUND_ROBIN;
        }
    }

    /**
     * 获取负载均衡器
     */
    public LoadBalancer getLoadBalancer() {
        return loadBalancer;
    }

    /**
     * 选择下一个后端
     */
    public Backend selectBackend() {
        return loadBalancer.selectBackend();
    }

    public CompletableFuture<Map<String, Boolean>> checkReadiness() {
        List<Backend> backends = loadBalancer.getBackends();
        if (isHealthCheckEnabled() || stopped.get() || backends.isEmpty()) {
            Map<String, Boolean> status = new HashMap<>();
            for (Backend backend : backends) {
                status.put(backend.getKey(),
                        !stopped.get() && verifiedBackends.contains(backend) && backend.isHealthy());
            }
            return CompletableFuture.completedFuture(Map.copyOf(status));
        }

        while (true) {
            CompletableFuture<Map<String, Boolean>> existing = readinessCheck.get();
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            CompletableFuture<Map<String, Boolean>> result = new CompletableFuture<>();
            if (!readinessCheck.compareAndSet(existing, result)) {
                continue;
            }

            Map<String, Boolean> status = new ConcurrentHashMap<>();
            AtomicInteger remaining = new AtomicInteger(backends.size());
            for (Backend backend : backends) {
                probeBackend(backend, false).whenComplete((healthy, failure) -> {
                    status.put(backend.getKey(), failure == null && Boolean.TRUE.equals(healthy));
                    if (remaining.decrementAndGet() == 0) {
                        result.complete(Map.copyOf(status));
                    }
                });
            }
            return result;
        }
    }

    /**
     * 启动健康检查
     */
    public void startHealthCheck() {
        if (stopped.get()) {
            return;
        }
        if (healthCheckConfig == null || !healthCheckConfig.enabled) {
            logger.info("Health check disabled");
            return;
        }

        if (running) {
            logger.warn("Health check already running");
            return;
        }

        running = true;
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-checker");
            t.setDaemon(true);
            return t;
        });

        healthCheckExecutor.scheduleAtFixedRate(
                this::checkAllBackends,
                0,
                healthCheckConfig.intervalSeconds,
                TimeUnit.SECONDS
        );

        logger.info("Health check started: interval={}s, path={}, timeout={}ms",
                healthCheckConfig.intervalSeconds,
                healthCheckConfig.path,
                healthCheckConfig.timeoutMs);
    }

    /**
     * 检查所有后端
     */
    private void checkAllBackends() {
        for (Backend backend : loadBalancer.getBackends()) {
            if (!running || !inFlightHealthChecks.add(backend)) {
                continue;
            }
            probeBackend(backend, true).whenComplete((healthy, failure) -> {
                try {
                    if (!running) {
                        return;
                    }
                    if (failure == null && Boolean.TRUE.equals(healthy)) {
                        verifiedBackends.add(backend);
                        onHealthCheckSuccess(backend, backend.getKey());
                    } else {
                        onHealthCheckFailure(backend, backend.getKey(), "Backend probe failed");
                    }
                } finally {
                    inFlightHealthChecks.remove(backend);
                }
            });
        }
    }

    /**
     * 检查单个后端健康状态
     */
    private CompletableFuture<Boolean> probeBackend(Backend backend, boolean httpCheck) {
        if (stopped.get()) {
            return CompletableFuture.completedFuture(false);
        }
        BackendProbe probe = new BackendProbe(backend, httpCheck);
        activeProbes.add(probe);
        try {
            probe.eventLoop.execute(probe::start);
        } catch (RuntimeException error) {
            probe.complete(false);
        }
        return probe.result;
    }

    private class BackendProbe extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Backend backend;
        private final boolean httpCheck;
        private final EventLoop eventLoop;
        private final CompletableFuture<Boolean> result = new CompletableFuture<>();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final int timeoutMillis;
        private volatile Channel channel;
        private volatile ScheduledFuture<?> deadline;

        BackendProbe(Backend backend, boolean httpCheck) {
            this.backend = backend;
            this.httpCheck = httpCheck;
            this.eventLoop = eventLoopGroup.next();
            this.timeoutMillis = httpCheck ? Math.max(1, healthCheckConfig.timeoutMs) : 2000;
        }

        private void start() {
            if (stopped.get()) {
                complete(false);
                return;
            }
            try {
                deadline = eventLoop.schedule(() -> complete(false), timeoutMillis, TimeUnit.MILLISECONDS);
                Bootstrap bootstrap = new Bootstrap()
                        .group(eventLoop)
                        .channel(TransportFactory.getSocketChannelClass())
                        .resolver(resolverGroup)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMillis)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel socket) {
                                if (httpCheck) {
                                    if ("https".equalsIgnoreCase(backend.getProtocol())) {
                                        if (healthCheckSslContext == null) {
                                            throw new IllegalStateException("HTTPS health check SSL context unavailable");
                                        }
                                        SslHandler sslHandler = healthCheckSslContext.newHandler(
                                                socket.alloc(), backend.getHost(), backend.getPort());
                                        TlsUtil.enableHostnameVerification(sslHandler.engine());
                                        sslHandler.setHandshakeTimeoutMillis(timeoutMillis);
                                        sslHandler.handshakeFuture().addListener(handshake -> {
                                            if (!handshake.isSuccess()) {
                                                complete(false);
                                            }
                                        });
                                        socket.pipeline().addLast("ssl", sslHandler);
                                    }
                                    socket.pipeline().addLast(
                                            new HttpClientCodec(), new HttpObjectAggregator(1024 * 1024));
                                }
                                socket.pipeline().addLast(BackendProbe.this);
                            }
                        });
                ChannelFuture connection = bootstrap.connect(backend.getHost(), backend.getPort());
                channel = connection.channel();
                if (completed.get()) {
                    channel.close();
                    return;
                }
                connection.addListener((ChannelFutureListener) connected -> {
                    if (!connected.isSuccess()) {
                        complete(false);
                    } else if (!httpCheck) {
                        complete(true);
                    } else if (!completed.get()) {
                        FullHttpRequest request = new DefaultFullHttpRequest(
                                HttpVersion.HTTP_1_1, HttpMethod.GET, healthCheckConfig.path, Unpooled.EMPTY_BUFFER);
                        request.headers().set(HttpHeaderNames.HOST, backend.getHost() + ":" + backend.getPort());
                        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        connected.channel().writeAndFlush(request).addListener(written -> {
                            if (!written.isSuccess()) {
                                complete(false);
                            }
                        });
                    }
                });
            } catch (RuntimeException error) {
                complete(false);
            }
        }

        private void complete(boolean healthy) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> timeout = deadline;
            if (timeout != null) {
                timeout.cancel(false);
            }
            Channel currentChannel = channel;
            if (currentChannel != null) {
                currentChannel.close();
            }
            activeProbes.remove(this);
            result.complete(healthy);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            int statusCode = response.status().code();
            if (statusCode < 200 && statusCode != 101 && response.decoderResult().isSuccess()) {
                return;
            }
            complete(response.decoderResult().isSuccess() && statusCode >= 200 && statusCode < 400);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            complete(false);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            complete(false);
            ctx.close();
        }
    }

    /**
     * 健康检查成功
     */
    private void onHealthCheckSuccess(Backend backend, String key) {
        // 重置失败计数，增加成功计数
        failureCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).set(0);
        int successes = successCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();

        if (!backend.isHealthy() && successes >= healthCheckConfig.healthyThreshold) {
            loadBalancer.markHealthy(backend.getHost(), backend.getPort());
            logger.info("Backend {} is now healthy after {} successful checks", key, successes);
        }

        backend.setLastHealthCheck(System.currentTimeMillis());
    }

    /**
     * 健康检查失败
     */
    private void onHealthCheckFailure(Backend backend, String key, String reason) {
        // 重置成功计数，增加失败计数
        successCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).set(0);
        int failures = failureCounts.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();

        if (backend.isHealthy() && failures >= healthCheckConfig.unhealthyThreshold) {
            loadBalancer.markUnhealthy(backend.getHost(), backend.getPort());
            logger.warn("Backend {} is now unhealthy after {} failed checks: {}", key, failures, reason);
        } else {
            logger.debug("Health check failed for {}: {} (failures: {})", key, reason, failures);
        }

        backend.setLastHealthCheck(System.currentTimeMillis());
    }

    /**
     * 停止健康检查
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        running = false;
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdownNow();
        }
        for (BackendProbe probe : activeProbes) {
            probe.complete(false);
        }
        resolverGroup.close();
        resolverExecutor.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        ReferenceCountUtil.release(healthCheckSslContext);
        logger.info("LoadBalancerManager stopped");
    }

    /**
     * 获取后端健康状态信息
     */
    public String getBackendStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("Load Balancer Stats (strategy: ").append(loadBalancer.getStrategy()).append(")\n");
        sb.append("Healthy backends: ").append(loadBalancer.getHealthyBackendCount())
                .append("/").append(loadBalancer.getBackends().size()).append("\n");

        for (Backend backend : loadBalancer.getBackends()) {
            sb.append("  - ").append(backend.toString()).append("\n");
        }
        return sb.toString();
    }
}
