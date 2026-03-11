package com.sslproxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 配置文件监视器
 * <p>
 * 使用 WatchService 监听配置文件变化，支持热更新以下配置：
 * - 限流参数 (rateLimit)
 * - SSL 指纹白名单/黑名单
 * <p>
 * 不支持热更新的配置（需要重启）：
 * - 服务器端口
 * - SSL 证书
 * - 后端目标地址
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ConfigWatcher implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConfigWatcher.class);

    private final Path configPath;
    private final WatchService watchService;
    private final ExecutorService executor;
    private final ScheduledExecutorService reloadScheduler;
    private final CopyOnWriteArrayList<Consumer<ProxyConfig>> listeners;
    private volatile boolean running;
    private final ProxyConfig currentConfig;
    private volatile ScheduledFuture<?> pendingReload;

    /**
     * 构造配置监视器
     *
     * @param configFilePath 配置文件路径
     * @param initialConfig  初始配置
     * @throws IOException 如果无法创建 WatchService
     */
    public ConfigWatcher(String configFilePath, ProxyConfig initialConfig) throws IOException {
        this.configPath = Paths.get(configFilePath).toAbsolutePath();
        this.currentConfig = initialConfig;
        this.listeners = new CopyOnWriteArrayList<>();
        this.watchService = FileSystems.getDefault().newWatchService();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "config-watcher");
            t.setDaemon(true);
            return t;
        });
        this.reloadScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-reload");
            t.setDaemon(true);
            return t;
        });
        this.running = false;

        logger.info("ConfigWatcher initialized for: {}", configPath);
    }

    /**
     * 添加配置变化监听器
     *
     * @param listener 监听器
     */
    public void addListener(Consumer<ProxyConfig> listener) {
        listeners.add(listener);
    }

    /**
     * 移除配置变化监听器
     *
     * @param listener 监听器
     */
    public void removeListener(Consumer<ProxyConfig> listener) {
        listeners.remove(listener);
    }

    /**
     * 启动配置监视
     */
    public void start() {
        if (running) {
            logger.warn("ConfigWatcher is already running");
            return;
        }

        try {
            Path parentDir = configPath.getParent();
            if (parentDir == null) {
                logger.error("Cannot determine parent directory for config file: {}", configPath);
                return;
            }

            parentDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE);

            running = true;
            executor.submit(this::watchLoop);
            logger.info("ConfigWatcher started, monitoring: {}", configPath);

        } catch (IOException e) {
            logger.error("Failed to start ConfigWatcher: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止配置监视
     */
    public void stop() {
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            logger.error("Error closing WatchService: {}", e.getMessage());
        }
        reloadScheduler.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("ConfigWatcher stopped");
    }

    /**
     * 监视循环
     */
    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changedPath = pathEvent.context();

                    // 检查是否是我们监视的配置文件
                    if (configPath.getFileName().equals(changedPath)) {
                        logger.info("Configuration file changed: {}", changedPath);
                        scheduleReload();
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    logger.warn("WatchKey is no longer valid, stopping ConfigWatcher");
                    break;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                logger.error("Error in watch loop: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 防抖调度配置重载：200ms 内多次文件变更事件只触发一次重载。
     * 不使用 Thread.sleep，通过 ScheduledExecutorService 异步执行。
     */
    private void scheduleReload() {
        ScheduledFuture<?> existing = pendingReload;
        if (existing != null) {
            existing.cancel(false);
        }
        pendingReload = reloadScheduler.schedule(this::reloadConfig, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * 重新加载配置
     */
    private void reloadConfig() {
        try {
            ProxyConfig newConfig = ProxyConfig.loadFromFile(configPath.toString());

            // 合并配置：只更新支持热加载的部分
            mergeHotReloadableConfig(currentConfig, newConfig);

            // 通知所有监听器
            for (Consumer<ProxyConfig> listener : listeners) {
                try {
                    listener.accept(currentConfig);
                } catch (Exception e) {
                    logger.error("Error notifying config listener: {}", e.getMessage(), e);
                }
            }

            logger.info("Configuration reloaded successfully");

        } catch (Exception e) {
            logger.error("Failed to reload configuration: {}", e.getMessage(), e);
        }
    }

    /**
     * 合并支持热加载的配置
     * <p>
     * 只更新以下配置项：
     * - rateLimit 限流配置
     * - ssl.whitelistFingerprints / ssl.blacklistFingerprints
     * </p>
     *
     * @param current 当前配置
     * @param updated 新配置
     */
    private void mergeHotReloadableConfig(ProxyConfig current, ProxyConfig updated) {
        // 更新限流配置
        if (updated.rateLimit != null) {
            if (current.rateLimit == null) {
                current.rateLimit = new ProxyConfig.RateLimit();
            }
            current.rateLimit.enabled = updated.rateLimit.enabled;
            current.rateLimit.globalRps = updated.rateLimit.globalRps;
            current.rateLimit.globalBurstCapacity = updated.rateLimit.globalBurstCapacity;
            current.rateLimit.clientRps = updated.rateLimit.clientRps;
            current.rateLimit.clientBurstCapacity = updated.rateLimit.clientBurstCapacity;
            logger.info("Rate limit config updated: enabled={}, globalRps={}",
                    current.rateLimit.enabled, current.rateLimit.globalRps);
        }

        // 更新 SSL 指纹白名单/黑名单
        if (updated.ssl != null) {
            if (current.ssl != null) {
                if (updated.ssl.whitelistFingerprints != null) {
                    current.ssl.whitelistFingerprints = updated.ssl.whitelistFingerprints;
                    logger.info("SSL whitelist fingerprints updated: {} entries",
                            current.ssl.whitelistFingerprints.size());
                }
                if (updated.ssl.blacklistFingerprints != null) {
                    current.ssl.blacklistFingerprints = updated.ssl.blacklistFingerprints;
                    logger.info("SSL blacklist fingerprints updated: {} entries",
                            current.ssl.blacklistFingerprints.size());
                }
            }
        }
    }

    /**
     * 获取当前配置
     *
     * @return 当前配置
     */
    public ProxyConfig getCurrentConfig() {
        return currentConfig;
    }

    /**
     * 手动触发配置重新加载
     */
    public void triggerReload() {
        reloadConfig();
    }

    @Override
    public void close() {
        stop();
    }
}
