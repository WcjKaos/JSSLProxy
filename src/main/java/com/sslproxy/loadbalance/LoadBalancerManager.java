package com.sslproxy.loadbalance;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.ProxyConfig.BackendConfig;
import com.sslproxy.config.ProxyConfig.HealthCheckConfig;
import com.sslproxy.loadbalance.LoadBalancer.Backend;
import com.sslproxy.loadbalance.LoadBalancer.Strategy;

import com.sslproxy.server.TransportFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.ReadTimeoutHandler;

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
    private ScheduledExecutorService healthCheckExecutor;
    private volatile boolean running;

    // 健康检查计数器（用于连续成功/失败计数）
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> successCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> failureCounts = new java.util.concurrent.ConcurrentHashMap<>();

    public LoadBalancerManager(ProxyConfig.Target targetConfig, EventLoopGroup eventLoopGroup) {
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

        logger.info("LoadBalancerManager initialized with {} backends, strategy: {}",
                loadBalancer.getBackends().size(), strategy);
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

    /**
     * 启动健康检查
     */
    public void startHealthCheck() {
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
            checkBackend(backend);
        }
    }

    /**
     * 检查单个后端健康状态
     */
    private void checkBackend(Backend backend) {
        String key = backend.getKey();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(eventLoopGroup)
                .channel(TransportFactory.getSocketChannelClass())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, healthCheckConfig.timeoutMs)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new ReadTimeoutHandler(healthCheckConfig.timeoutMs, TimeUnit.MILLISECONDS),
                                new HttpClientCodec(),
                                new HttpObjectAggregator(1024 * 1024),
                                new HealthCheckHandler(backend, key)
                        );
                    }
                });

        bootstrap.connect(backend.getHost(), backend.getPort())
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        // 发送 HTTP GET 请求
                        Channel channel = future.channel();
                        HttpRequest request = new DefaultFullHttpRequest(
                                HttpVersion.HTTP_1_1,
                                HttpMethod.GET,
                                healthCheckConfig.path,
                                Unpooled.EMPTY_BUFFER
                        );
                        request.headers().set(HttpHeaderNames.HOST, backend.getHost() + ":" + backend.getPort());
                        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
                        channel.writeAndFlush(request);
                    } else {
                        // 连接失败
                        onHealthCheckFailure(backend, key, "Connection failed: " +
                                (future.cause() != null ? future.cause().getMessage() : "unknown"));
                    }
                });
    }

    /**
     * 健康检查响应处理器
     */
    private class HealthCheckHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Backend backend;
        private final String key;

        HealthCheckHandler(Backend backend, String key) {
            this.backend = backend;
            this.key = key;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            int statusCode = response.status().code();
            if (statusCode >= 200 && statusCode < 400) {
                onHealthCheckSuccess(backend, key);
            } else {
                onHealthCheckFailure(backend, key, "Unhealthy status: " + statusCode);
            }
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            onHealthCheckFailure(backend, key, cause.getMessage());
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
        running = false;
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
            try {
                if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthCheckExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
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
