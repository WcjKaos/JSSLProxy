package com.sslproxy.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.sslproxy.config.ProxyConfig;

/**
 * 限流热加载测试
 * <p>
 * 测试限流配置热加载时的令牌桶迁移功能，确保不会被利用绕过限流
 * </p>
 */
public class RateLimitHotReloadTest {

    /**
     * 测试：热加载保留客户端令牌桶状态
     */
    @Test
    public void testHotReload_PreservesClientBucketState() {
        // 1. 初始化限流器：clientRps=10, clientBurstCapacity=10
        ProxyConfig.RateLimit config1 = createRateLimitConfig(100, 100, 10, 10);
        RateLimiterManager manager1 = new RateLimiterManager(config1);

        String clientFingerprint = "test-client-fingerprint-12345";

        // 2. 消耗 7 个令牌（剩余 3 个）
        for (int i = 0; i < 7; i++) {
            assertTrue(manager1.tryConsumeClient(clientFingerprint),
                "Should allow request " + (i + 1));
        }

        // 3. 热加载配置：clientRps=20, clientBurstCapacity=20
        ProxyConfig.RateLimit config2 = createRateLimitConfig(100, 100, 20, 20);
        RateLimiterManager manager2 = manager1.reload(config2);

        // 4. 验证：新桶应该只有 3 个可用令牌（保留了旧状态）
        // 新桶容量 20，旧桶剩余 3，所以应该消耗了 17 个令牌
        assertTrue(manager2.tryConsumeClient(clientFingerprint), "Should allow 1st request");
        assertTrue(manager2.tryConsumeClient(clientFingerprint), "Should allow 2nd request");
        assertTrue(manager2.tryConsumeClient(clientFingerprint), "Should allow 3rd request");

        // 第 4 个请求应该被限流（因为只剩 3 个令牌）
        assertFalse(manager2.tryConsumeClient(clientFingerprint),
            "Should rate limit 4th request (bucket state preserved)");
    }

    /**
     * 测试：热加载应用新的限流配置
     */
    @Test
    public void testHotReload_AppliesNewRateLimits() {
        // 1. 初始化限流器：clientRps=5, clientBurstCapacity=5
        ProxyConfig.RateLimit config1 = createRateLimitConfig(100, 100, 5, 5);
        RateLimiterManager manager1 = new RateLimiterManager(config1);

        String clientFingerprint = "test-client-fingerprint-67890";

        // 2. 消耗所有 5 个令牌
        for (int i = 0; i < 5; i++) {
            assertTrue(manager1.tryConsumeClient(clientFingerprint));
        }

        // 3. 第 6 个请求被限流
        assertFalse(manager1.tryConsumeClient(clientFingerprint));

        // 4. 热加载配置：clientRps=15, clientBurstCapacity=15
        ProxyConfig.RateLimit config2 = createRateLimitConfig(100, 100, 15, 15);
        RateLimiterManager manager2 = manager1.reload(config2);

        // 5. 验证：新桶应该没有可用令牌（因为旧桶已耗尽）
        assertFalse(manager2.tryConsumeClient(clientFingerprint),
            "Should still be rate limited after reload (old bucket was exhausted)");
    }

    /**
     * 测试：热加载处理从禁用到启用
     */
    @Test
    public void testHotReload_HandlesDisableToEnable() {
        // 1. 初始化为禁用状态
        ProxyConfig.RateLimit config1 = createRateLimitConfig(100, 100, 10, 10);
        config1.enabled = false;
        RateLimiterManager manager1 = new RateLimiterManager(config1);

        assertFalse(manager1.isEnabled());

        String clientFingerprint = "test-client-fingerprint-abc";

        // 2. 禁用时所有请求都通过
        for (int i = 0; i < 20; i++) {
            assertTrue(manager1.tryConsumeClient(clientFingerprint));
        }

        // 3. 热加载启用限流
        ProxyConfig.RateLimit config2 = createRateLimitConfig(100, 100, 10, 10);
        config2.enabled = true;
        RateLimiterManager manager2 = manager1.reload(config2);

        assertTrue(manager2.isEnabled());

        // 4. 验证：启用后限流生效（新桶有 10 个令牌）
        for (int i = 0; i < 10; i++) {
            assertTrue(manager2.tryConsumeClient(clientFingerprint),
                "Should allow request " + (i + 1));
        }

        // 第 11 个请求被限流
        assertFalse(manager2.tryConsumeClient(clientFingerprint),
            "Should rate limit 11th request");
    }

    /**
     * 测试：热加载处理从启用到禁用
     */
    @Test
    public void testHotReload_HandlesEnableToDisable() {
        // 1. 初始化为启用状态
        ProxyConfig.RateLimit config1 = createRateLimitConfig(100, 100, 5, 5);
        config1.enabled = true;
        RateLimiterManager manager1 = new RateLimiterManager(config1);

        assertTrue(manager1.isEnabled());

        String clientFingerprint = "test-client-fingerprint-xyz";

        // 2. 消耗所有令牌
        for (int i = 0; i < 5; i++) {
            assertTrue(manager1.tryConsumeClient(clientFingerprint));
        }

        // 3. 第 6 个请求被限流
        assertFalse(manager1.tryConsumeClient(clientFingerprint));

        // 4. 热加载禁用限流
        ProxyConfig.RateLimit config2 = createRateLimitConfig(100, 100, 5, 5);
        config2.enabled = false;
        RateLimiterManager manager2 = manager1.reload(config2);

        assertFalse(manager2.isEnabled());

        // 5. 验证：禁用后所有请求都通过
        for (int i = 0; i < 20; i++) {
            assertTrue(manager2.tryConsumeClient(clientFingerprint),
                "Should allow all requests when disabled");
        }
    }

    /**
     * 测试：防止限流绕过攻击
     * <p>
     * 攻击场景：客户端消耗完令牌后，触发配置重载以重置限流
     * </p>
     */
    @Test
    public void testRateLimitBypassPrevention() {
        // 1. 初始化限流器：clientRps=10, clientBurstCapacity=10
        ProxyConfig.RateLimit config = createRateLimitConfig(100, 100, 10, 10);
        RateLimiterManager manager1 = new RateLimiterManager(config);

        String attackerFingerprint = "attacker-fingerprint-12345";

        // 2. 攻击者消耗所有 10 个令牌
        for (int i = 0; i < 10; i++) {
            assertTrue(manager1.tryConsumeClient(attackerFingerprint));
        }

        // 3. 攻击者被限流
        assertFalse(manager1.tryConsumeClient(attackerFingerprint),
            "Attacker should be rate limited");

        // 4. 攻击者尝试通过触发配置重载来绕过限流
        RateLimiterManager manager2 = manager1.reload(config);

        // 5. 验证：攻击者仍然被限流（令牌桶状态被保留）
        assertFalse(manager2.tryConsumeClient(attackerFingerprint),
            "Attacker should still be rate limited after reload (bypass prevented)");
    }

    /**
     * 测试：热加载不影响其他客户端
     */
    @Test
    public void testHotReload_DoesNotAffectOtherClients() {
        // 1. 初始化限流器
        ProxyConfig.RateLimit config1 = createRateLimitConfig(100, 100, 10, 10);
        RateLimiterManager manager1 = new RateLimiterManager(config1);

        String client1 = "client-1-fingerprint";
        String client2 = "client-2-fingerprint";

        // 2. 客户端 1 消耗 8 个令牌
        for (int i = 0; i < 8; i++) {
            assertTrue(manager1.tryConsumeClient(client1));
        }

        // 3. 客户端 2 消耗 3 个令牌
        for (int i = 0; i < 3; i++) {
            assertTrue(manager1.tryConsumeClient(client2));
        }

        // 4. 热加载配置
        ProxyConfig.RateLimit config2 = createRateLimitConfig(100, 100, 10, 10);
        RateLimiterManager manager2 = manager1.reload(config2);

        // 5. 验证：客户端 1 剩余 2 个令牌
        assertTrue(manager2.tryConsumeClient(client1));
        assertTrue(manager2.tryConsumeClient(client1));
        assertFalse(manager2.tryConsumeClient(client1));

        // 6. 验证：客户端 2 剩余 7 个令牌
        for (int i = 0; i < 7; i++) {
            assertTrue(manager2.tryConsumeClient(client2),
                "Client 2 should have 7 tokens remaining");
        }
        assertFalse(manager2.tryConsumeClient(client2));
    }

    /**
     * 测试：全局限流桶不迁移（每次重新创建）
     */
    @Test
    public void testHotReload_GlobalBucketRecreated() {
        // 1. 初始化限流器：globalRps=5, globalBurstCapacity=5
        ProxyConfig.RateLimit config1 = createRateLimitConfig(5, 5, 100, 100);
        RateLimiterManager manager1 = new RateLimiterManager(config1);

        // 2. 消耗所有全局令牌
        for (int i = 0; i < 5; i++) {
            assertTrue(manager1.tryConsumeGlobal());
        }

        // 3. 第 6 个请求被限流
        assertFalse(manager1.tryConsumeGlobal());

        // 4. 热加载配置：globalRps=10, globalBurstCapacity=10
        ProxyConfig.RateLimit config2 = createRateLimitConfig(10, 10, 100, 100);
        RateLimiterManager manager2 = manager1.reload(config2);

        // 5. 验证：全局桶被重新创建，有 10 个新令牌
        for (int i = 0; i < 10; i++) {
            assertTrue(manager2.tryConsumeGlobal(),
                "Global bucket should be recreated with 10 tokens");
        }

        // 第 11 个请求被限流
        assertFalse(manager2.tryConsumeGlobal());
    }

    // ========== 辅助方法 ==========

    /**
     * 创建限流配置
     */
    private ProxyConfig.RateLimit createRateLimitConfig(
            int globalRps, int globalBurst, int clientRps, int clientBurst) {
        ProxyConfig.RateLimit config = new ProxyConfig.RateLimit();
        config.enabled = true;
        config.globalRps = globalRps;
        config.globalBurstCapacity = globalBurst;
        config.clientRps = clientRps;
        config.clientBurstCapacity = clientBurst;
        return config;
    }
}
