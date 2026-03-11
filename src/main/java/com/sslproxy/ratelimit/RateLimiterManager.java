package com.sslproxy.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.config.ProxyConfig;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 限流管理器
 * <p>
 * 基于 Bucket4j 令牌桶算法实现的限流管理器。
 * 支持：
 * - 全局限流：整个代理共用一个令牌桶
 * - 客户端限流：每个客户端证书独立令牌桶（带 LRU 过期清理）
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.1
 */
public class RateLimiterManager {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiterManager.class);

    /** 默认最大客户端桶数量 */
    private static final int DEFAULT_MAX_CLIENT_BUCKETS = 10000;

    /** 默认客户端桶空闲过期时间（分钟） */
    private static final int DEFAULT_BUCKET_EXPIRE_MINUTES = 5;

    /** 全局限流桶 */
    private final Bucket globalBucket;

    /** 按客户端证书指纹的限流桶（使用 Caffeine 缓存，支持 LRU 过期） */
    private final Cache<String, Bucket> clientBuckets;

    /** 配置 */
    private final ProxyConfig.RateLimit config;

    /** 是否启用 */
    private final boolean enabled;

    /** 驱逐计数器 */
    private final AtomicLong evictionCount = new AtomicLong(0);

    /**
     * 构造函数
     *
     * @param config 限流配置
     */
    public RateLimiterManager(ProxyConfig.RateLimit config) {
        this.config = config;
        this.enabled = config != null && config.enabled;

        if (enabled) {
            // 创建全局限流桶
            this.globalBucket = createBucket(config.globalRps, config.globalBurstCapacity);

            // 创建客户端桶缓存（带 LRU 过期清理）
            this.clientBuckets = Caffeine.newBuilder()
                    .maximumSize(DEFAULT_MAX_CLIENT_BUCKETS)
                    .expireAfterAccess(DEFAULT_BUCKET_EXPIRE_MINUTES, TimeUnit.MINUTES)
                    .removalListener((String key, Bucket value, RemovalCause cause) -> {
                        if (cause.wasEvicted()) {
                            evictionCount.incrementAndGet();
                            logger.debug("Client bucket evicted: fingerprint={}, cause={}",
                                    key != null ? key.substring(0, Math.min(16, key.length())) + "..." : "null",
                                    cause);
                        }
                    })
                    .build();

            logger.info("RateLimiterManager initialized: globalRps={}, globalBurst={}, clientRps={}, clientBurst={}, maxBuckets={}, expireMinutes={}",
                    config.globalRps, config.globalBurstCapacity, config.clientRps, config.clientBurstCapacity,
                    DEFAULT_MAX_CLIENT_BUCKETS, DEFAULT_BUCKET_EXPIRE_MINUTES);
        } else {
            this.globalBucket = null;
            this.clientBuckets = null;
            logger.info("RateLimiterManager disabled");
        }
    }

    /**
     * 使用新配置重新初始化，尽可能保留现有令牌桶状态。
     * <p>
     * 用于配置热加载场景，会尝试迁移客户端令牌桶状态以防止限流绕过。
     * 返回新实例，调用方应替换对旧实例的引用。
     * </p>
     *
     * @param newConfig 新的限流配置
     * @return 新的 RateLimiterManager 实例
     */
    public RateLimiterManager reload(ProxyConfig.RateLimit newConfig) {
        RateLimiterManager newInstance = new RateLimiterManager(newConfig);

        // 如果新旧实例都启用，迁移客户端令牌桶
        if (this.enabled && newInstance.enabled) {
            migrateClientBuckets(this, newInstance);
        }

        logger.info("RateLimiterManager reloaded (hot reload)");
        return newInstance;
    }

    /**
     * 从旧实例迁移客户端令牌桶到新实例
     */
    private static void migrateClientBuckets(RateLimiterManager oldMgr, RateLimiterManager newMgr) {
        if (oldMgr.clientBuckets == null || newMgr.clientBuckets == null) {
            return;
        }

        int migratedCount = 0;
        for (var entry : oldMgr.clientBuckets.asMap().entrySet()) {
            String fingerprint = entry.getKey();
            Bucket oldBucket = entry.getValue();

            Bucket newBucket = newMgr.createBucket(
                newMgr.config.clientRps,
                newMgr.config.clientBurstCapacity);

            long oldAvailable = oldBucket.getAvailableTokens();
            long newCapacity = newMgr.config.clientBurstCapacity;

            if (oldAvailable < newCapacity) {
                long tokensToConsume = newCapacity - oldAvailable;
                newBucket.tryConsume(tokensToConsume);
            }

            newMgr.clientBuckets.put(fingerprint, newBucket);
            migratedCount++;
        }

        logger.info("Migrated {} client rate limit buckets", migratedCount);
    }

    /**
     * 检查是否启用
     *
     * @return true 如果启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 尝试消费全局令牌
     *
     * @return true 如果允许请求
     */
    public boolean tryConsumeGlobal() {
        if (!enabled || globalBucket == null) {
            return true;
        }
        return globalBucket.tryConsume(1);
    }

    /**
     * 尝试消费客户端令牌
     *
     * @param clientFingerprint 客户端证书指纹
     * @return true 如果允许请求
     */
    public boolean tryConsumeClient(String clientFingerprint) {
        if (!enabled || clientFingerprint == null || clientFingerprint.isEmpty()) {
            return true;
        }

        Bucket clientBucket = clientBuckets.get(clientFingerprint,
                fp -> createBucket(config.clientRps, config.clientBurstCapacity));

        return clientBucket.tryConsume(1);
    }

    /**
     * 双层限流检查
     *
     * @param clientFingerprint 客户端证书指纹（可以为 null）
     * @return RateLimitResult 限流结果
     */
    public RateLimitResult tryConsume(String clientFingerprint) {
        if (!enabled) {
            return RateLimitResult.ALLOWED;
        }

        // 先检查全局限流
        if (!tryConsumeGlobal()) {
            logger.debug("Global rate limit exceeded");
            return RateLimitResult.GLOBAL_LIMITED;
        }

        // 再检查客户端限流
        if (clientFingerprint != null && !clientFingerprint.isEmpty()) {
            if (!tryConsumeClient(clientFingerprint)) {
                logger.debug("Client rate limit exceeded for fingerprint: {}",
                        clientFingerprint.substring(0, Math.min(16, clientFingerprint.length())) + "...");
                return RateLimitResult.CLIENT_LIMITED;
            }
        }

        return RateLimitResult.ALLOWED;
    }

    /**
     * 创建限流桶
     *
     * @param rps           每秒请求数
     * @param burstCapacity 突发容量
     * @return Bucket 实例
     */
    private Bucket createBucket(int rps, int burstCapacity) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(burstCapacity)
                .refillGreedy(rps, Duration.ofSeconds(1))
                .build();

        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    /**
     * 获取客户端桶数量（用于监控）
     *
     * @return 客户端桶数量
     */
    public long getClientBucketCount() {
        if (clientBuckets == null) {
            return 0;
        }
        return clientBuckets.estimatedSize();
    }

    /**
     * 获取驱逐次数（用于监控）
     *
     * @return 驱逐次数
     */
    public long getEvictionCount() {
        return evictionCount.get();
    }

    /**
     * 清理过期的客户端桶
     * <p>
     * 触发 Caffeine 缓存的清理操作，移除过期条目。
     * 通常不需要手动调用，Caffeine 会自动清理。
     * </p>
     */
    public void cleanupIdleBuckets() {
        if (clientBuckets != null) {
            clientBuckets.cleanUp();
            logger.debug("Client bucket cleanup completed, current count: {}, total evictions: {}",
                    clientBuckets.estimatedSize(), evictionCount.get());
        }
    }

    /**
     * 使指定客户端的桶失效
     *
     * @param clientFingerprint 客户端证书指纹
     */
    public void invalidateClient(String clientFingerprint) {
        if (clientBuckets != null && clientFingerprint != null) {
            clientBuckets.invalidate(clientFingerprint);
            logger.debug("Invalidated client bucket for fingerprint: {}",
                    clientFingerprint.substring(0, Math.min(16, clientFingerprint.length())) + "...");
        }
    }

    /**
     * 限流结果枚举
     */
    public enum RateLimitResult {
        /** 允许请求 */
        ALLOWED,
        /** 全局限流触发 */
        GLOBAL_LIMITED,
        /** 客户端限流触发 */
        CLIENT_LIMITED
    }
}
