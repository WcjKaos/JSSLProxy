package com.sslproxy.ratelimit;

import com.sslproxy.config.ProxyConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimiterManager 单元测试
 */
class RateLimiterManagerTest {

    @Test
    void testInitializeEnabled() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 100, 50, 10, 5);

        RateLimiterManager manager = new RateLimiterManager(config);

        assertNotNull(manager);
        assertTrue(manager.isEnabled());
    }

    @Test
    void testInitializeDisabled() {
        ProxyConfig.RateLimit config = createRateLimitConfig(false, 100, 50, 10, 5);

        RateLimiterManager manager = new RateLimiterManager(config);

        assertNotNull(manager);
        assertFalse(manager.isEnabled());
    }

    @Test
    void testInitializeWithNullConfig() {
        RateLimiterManager manager = new RateLimiterManager(null);

        assertNotNull(manager);
        assertFalse(manager.isEnabled());
    }

    @Test
    void testTryConsumeGlobalWhenDisabled() {
        ProxyConfig.RateLimit config = createRateLimitConfig(false, 100, 50, 10, 5);

        RateLimiterManager manager = new RateLimiterManager(config);

        // 禁用时应始终允许
        assertTrue(manager.tryConsumeGlobal());
    }

    @Test
    void testTryConsumeGlobalWithinLimit() {
        // 设置较高的限流值
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 100, 10);

        RateLimiterManager manager = new RateLimiterManager(config);

        // 连续请求应该都能通过
        for (int i = 0; i < 50; i++) {
            assertTrue(manager.tryConsumeGlobal(), "Request " + i + " should pass");
        }
    }

    @Test
    void testTryConsumeGlobalExceedsLimit() {
        // 设置非常低的限流值
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1, 3, 1, 1);

        RateLimiterManager manager = new RateLimiterManager(config);

        // 消耗掉所有令牌
        int allowed = 0;
        int denied = 0;
        for (int i = 0; i < 10; i++) {
            if (manager.tryConsumeGlobal()) {
                allowed++;
            } else {
                denied++;
            }
        }

        // 应该有一些被拒绝
        assertTrue(denied > 0, "Some requests should be denied");
        assertTrue(allowed > 0, "Some requests should be allowed");
    }

    @Test
    void testTryConsumeClientWhenDisabled() {
        ProxyConfig.RateLimit config = createRateLimitConfig(false, 100, 50, 10, 5);

        RateLimiterManager manager = new RateLimiterManager(config);

        // 禁用时应始终允许
        assertTrue(manager.tryConsumeClient("client-fingerprint"));
    }

    @Test
    void testTryConsumeClientWithNullFingerprint() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 100, 50, 10, 5);

        RateLimiterManager manager = new RateLimiterManager(config);

        // null 指纹应始终允许
        assertTrue(manager.tryConsumeClient(null));
        assertTrue(manager.tryConsumeClient(""));
    }

    @Test
    void testTryConsumeClientWithinLimit() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 100, 20);

        RateLimiterManager manager = new RateLimiterManager(config);

        String fingerprint = "CLIENT:FP:123456";

        // 连续请求应该都能通过
        for (int i = 0; i < 15; i++) {
            assertTrue(manager.tryConsumeClient(fingerprint), "Request " + i + " should pass");
        }
    }

    @Test
    void testClientBucketsAreIsolated() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 5, 3);

        RateLimiterManager manager = new RateLimiterManager(config);

        String client1 = "CLIENT:1:AAAA";
        String client2 = "CLIENT:2:BBBB";

        // 消耗 client1 的所有令牌
        for (int i = 0; i < 10; i++) {
            manager.tryConsumeClient(client1);
        }

        // client2 应该不受影响
        assertTrue(manager.tryConsumeClient(client2), "Client 2 should not be affected");
    }

    @Test
    void testTryConsumeDoubleLayer() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 100, 20);

        RateLimiterManager manager = new RateLimiterManager(config);

        String fingerprint = "CLIENT:FP:XYZ";

        // 应该都允许
        RateLimiterManager.RateLimitResult result = manager.tryConsume(fingerprint);
        assertEquals(RateLimiterManager.RateLimitResult.ALLOWED, result);
    }

    @Test
    void testTryConsumeWhenDisabled() {
        ProxyConfig.RateLimit config = createRateLimitConfig(false, 100, 50, 10, 5);

        RateLimiterManager manager = new RateLimiterManager(config);

        RateLimiterManager.RateLimitResult result = manager.tryConsume("any-fingerprint");
        assertEquals(RateLimiterManager.RateLimitResult.ALLOWED, result);
    }

    @Test
    void testGetClientBucketCount() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 100, 20);

        RateLimiterManager manager = new RateLimiterManager(config);

        assertEquals(0L, manager.getClientBucketCount());

        manager.tryConsumeClient("client1");
        assertEquals(1L, manager.getClientBucketCount());

        manager.tryConsumeClient("client2");
        assertEquals(2L, manager.getClientBucketCount());

        // 同一个 client 不应增加计数
        manager.tryConsumeClient("client1");
        assertEquals(2L, manager.getClientBucketCount());
    }

    @Test
    void testCleanupIdleBuckets() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 100, 20);

        RateLimiterManager manager = new RateLimiterManager(config);

        // 不应抛出异常
        assertDoesNotThrow(() -> manager.cleanupIdleBuckets());
    }

    @Test
    void testGetEvictionCount() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 100, 20);

        RateLimiterManager manager = new RateLimiterManager(config);

        // 初始驱逐次数应为 0
        assertEquals(0L, manager.getEvictionCount());
    }

    @Test
    void testInvalidateClient() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 100, 20);

        RateLimiterManager manager = new RateLimiterManager(config);

        // 创建一个客户端桶
        manager.tryConsumeClient("client-to-invalidate");
        assertEquals(1L, manager.getClientBucketCount());

        // 使该客户端桶失效
        manager.invalidateClient("client-to-invalidate");

        // 触发清理
        manager.cleanupIdleBuckets();

        // 验证桶已被移除
        assertEquals(0L, manager.getClientBucketCount());
    }

    @Test
    void testInvalidateClientWithNull() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 1000, 100, 100, 20);

        RateLimiterManager manager = new RateLimiterManager(config);

        // 不应抛出异常
        assertDoesNotThrow(() -> manager.invalidateClient(null));
    }

    @Test
    void testNewInstancePerConstruction() {
        ProxyConfig.RateLimit config = createRateLimitConfig(true, 100, 50, 10, 5);

        RateLimiterManager manager1 = new RateLimiterManager(config);
        RateLimiterManager manager2 = new RateLimiterManager(config);

        assertNotNull(manager1);
        assertNotNull(manager2);
        assertNotSame(manager1, manager2);
    }

    @Test
    void testHotReload_ReloadReplacesInstance() {
        // Initial config: enabled
        ProxyConfig.RateLimit config1 = createRateLimitConfig(true, 100, 50, 10, 5);
        RateLimiterManager manager1 = new RateLimiterManager(config1);
        assertTrue(manager1.isEnabled());

        // Hot-reload with new config: disabled
        ProxyConfig.RateLimit config2 = createRateLimitConfig(false, 200, 100, 20, 10);
        RateLimiterManager manager2 = manager1.reload(config2);

        // Instance should be replaced and reflect new config
        assertNotSame(manager1, manager2);
        assertFalse(manager2.isEnabled());
    }

    @Test
    void testHotReload_NewLimitsApplied() {
        // Initial config: very restrictive (1 RPS, burst 2)
        ProxyConfig.RateLimit config1 = createRateLimitConfig(true, 1, 2, 1, 2);
        RateLimiterManager manager = new RateLimiterManager(config1);

        // Exhaust tokens
        manager.tryConsumeGlobal();
        manager.tryConsumeGlobal();
        boolean beforeReload = manager.tryConsumeGlobal();

        // Hot-reload with generous limits
        ProxyConfig.RateLimit config2 = createRateLimitConfig(true, 1000, 100, 1000, 100);
        RateLimiterManager reloaded = manager.reload(config2);

        // New instance should have fresh tokens
        boolean afterReload = reloaded.tryConsumeGlobal();

        assertFalse(beforeReload, "Should be rate limited before reload");
        assertTrue(afterReload, "Should have fresh tokens after reload");
    }

    private ProxyConfig.RateLimit createRateLimitConfig(boolean enabled, int globalRps,
                                                         int globalBurst, int clientRps, int clientBurst) {
        ProxyConfig.RateLimit config = new ProxyConfig.RateLimit();
        config.enabled = enabled;
        config.globalRps = globalRps;
        config.globalBurstCapacity = globalBurst;
        config.clientRps = clientRps;
        config.clientBurstCapacity = clientBurst;
        return config;
    }
}
