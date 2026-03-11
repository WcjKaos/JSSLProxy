package com.sslproxy.loadbalance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 负载均衡器
 * <p>
 * 支持多种负载均衡策略：
 * - ROUND_ROBIN: 轮询
 * - WEIGHTED_ROUND_ROBIN: 加权轮询
 * - LEAST_CONNECTIONS: 最少连接
 * - RANDOM: 随机
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class LoadBalancer {
    private static final Logger logger = LoggerFactory.getLogger(LoadBalancer.class);

    /**
     * 负载均衡策略
     */
    public enum Strategy {
        ROUND_ROBIN,
        WEIGHTED_ROUND_ROBIN,
        LEAST_CONNECTIONS,
        RANDOM
    }

    /**
     * 后端服务器信息
     */
    public static class Backend {
        private final String host;
        private final int port;
        private final String protocol;
        private final int weight;
        private final AtomicInteger activeConnections;
        private final AtomicLong totalRequests;
        private volatile boolean healthy;
        private volatile long lastHealthCheck;

        public Backend(String host, int port, String protocol, int weight) {
            this.host = host;
            this.port = port;
            this.protocol = protocol;
            this.weight = weight > 0 ? weight : 1;
            this.activeConnections = new AtomicInteger(0);
            this.totalRequests = new AtomicLong(0);
            this.healthy = true;
            this.lastHealthCheck = System.currentTimeMillis();
        }

        public Backend(String host, int port, String protocol) {
            this(host, port, protocol, 1);
        }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getProtocol() { return protocol; }
        public int getWeight() { return weight; }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        public int getActiveConnections() { return activeConnections.get(); }
        public void incrementConnections() { activeConnections.incrementAndGet(); totalRequests.incrementAndGet(); }
        public void decrementConnections() { activeConnections.updateAndGet(val -> Math.max(0, val - 1)); }
        public long getTotalRequests() { return totalRequests.get(); }
        public long getLastHealthCheck() { return lastHealthCheck; }
        public void setLastHealthCheck(long time) { this.lastHealthCheck = time; }

        public String getKey() {
            return host + ":" + port;
        }

        @Override
        public String toString() {
            return String.format("Backend{%s://%s:%d, weight=%d, healthy=%s, connections=%d}",
                    protocol, host, port, weight, healthy, activeConnections.get());
        }
    }

    private final List<Backend> backends;
    private final Strategy strategy;
    private final AtomicInteger roundRobinIndex;

    /**
     * 构造负载均衡器
     *
     * @param strategy 负载均衡策略
     */
    public LoadBalancer(Strategy strategy) {
        this.backends = new CopyOnWriteArrayList<>();
        this.strategy = strategy;
        this.roundRobinIndex = new AtomicInteger(0);
        logger.info("LoadBalancer initialized with strategy: {}", strategy);
    }

    /**
     * 使用默认策略（轮询）构造负载均衡器
     */
    public LoadBalancer() {
        this(Strategy.ROUND_ROBIN);
    }

    /**
     * 添加后端服务器
     *
     * @param backend 后端服务器
     */
    public void addBackend(Backend backend) {
        backends.add(backend);
        logger.info("Backend added: {}", backend);
    }

    /**
     * 移除后端服务器
     *
     * @param host 主机名
     * @param port 端口
     */
    public void removeBackend(String host, int port) {
        backends.removeIf(b -> b.getHost().equals(host) && b.getPort() == port);
        logger.info("Backend removed: {}:{}", host, port);
    }

    /**
     * 选择下一个可用的后端服务器
     *
     * @return 后端服务器，如果没有可用的则返回 null
     */
    public Backend selectBackend() {
        List<Backend> healthyBackends = backends.stream()
                .filter(Backend::isHealthy)
                .toList();

        if (healthyBackends.isEmpty()) {
            logger.warn("No healthy backends available");
            return null;
        }

        return switch (strategy) {
            case ROUND_ROBIN -> selectRoundRobin(healthyBackends);
            case WEIGHTED_ROUND_ROBIN -> selectWeightedRoundRobin(healthyBackends);
            case LEAST_CONNECTIONS -> selectLeastConnections(healthyBackends);
            case RANDOM -> selectRandom(healthyBackends);
        };
    }

    /**
     * 轮询选择
     */
    private Backend selectRoundRobin(List<Backend> backends) {
        int index = (roundRobinIndex.getAndIncrement() & 0x7FFFFFFF) % backends.size();
        return backends.get(index);
    }

    /**
     * 加权轮询选择
     */
    private Backend selectWeightedRoundRobin(List<Backend> backends) {
        int totalWeight = backends.stream().mapToInt(Backend::getWeight).sum();
        int randomWeight = (int) (Math.random() * totalWeight);

        int currentWeight = 0;
        for (Backend backend : backends) {
            currentWeight += backend.getWeight();
            if (randomWeight < currentWeight) {
                return backend;
            }
        }

        return backends.get(0);
    }

    /**
     * 最少连接选择
     */
    private Backend selectLeastConnections(List<Backend> backends) {
        return backends.stream()
                .min((a, b) -> {
                    // 考虑权重：连接数/权重
                    double aScore = (double) a.getActiveConnections() / a.getWeight();
                    double bScore = (double) b.getActiveConnections() / b.getWeight();
                    return Double.compare(aScore, bScore);
                })
                .orElse(backends.get(0));
    }

    /**
     * 随机选择
     */
    private Backend selectRandom(List<Backend> backends) {
        int index = (int) (Math.random() * backends.size());
        return backends.get(index);
    }

    /**
     * 获取所有后端服务器
     *
     * @return 后端服务器列表
     */
    public List<Backend> getBackends() {
        return List.copyOf(backends);
    }

    /**
     * 获取健康的后端数量
     *
     * @return 健康后端数量
     */
    public long getHealthyBackendCount() {
        return backends.stream().filter(Backend::isHealthy).count();
    }

    /**
     * 获取负载均衡策略
     *
     * @return 策略
     */
    public Strategy getStrategy() {
        return strategy;
    }

    /**
     * 标记后端为不健康
     *
     * @param host 主机名
     * @param port 端口
     */
    public void markUnhealthy(String host, int port) {
        backends.stream()
                .filter(b -> b.getHost().equals(host) && b.getPort() == port)
                .findFirst()
                .ifPresent(b -> {
                    b.setHealthy(false);
                    logger.warn("Backend marked as unhealthy: {}:{}", host, port);
                });
    }

    /**
     * 标记后端为健康
     *
     * @param host 主机名
     * @param port 端口
     */
    public void markHealthy(String host, int port) {
        backends.stream()
                .filter(b -> b.getHost().equals(host) && b.getPort() == port)
                .findFirst()
                .ifPresent(b -> {
                    b.setHealthy(true);
                    b.setLastHealthCheck(System.currentTimeMillis());
                    logger.info("Backend marked as healthy: {}:{}", host, port);
                });
    }
}
