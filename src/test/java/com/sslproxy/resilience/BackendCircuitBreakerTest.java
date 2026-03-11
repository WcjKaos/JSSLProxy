package com.sslproxy.resilience;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sslproxy.audit.AuditLogger;
import com.sslproxy.config.ProxyConfig.CircuitBreaker;
import com.sslproxy.metrics.ProxyMetrics;

/**
 * BackendCircuitBreaker 单元测试
 * <p>
 * 测试基于 Resilience4j 实现的熔断器功能。
 * </p>
 */
class BackendCircuitBreakerTest {

    private CircuitBreaker config;

    @BeforeEach
    void setUp() {
        // 创建测试配置
        config = new CircuitBreaker();
        config.enabled = true;
        config.failureRateThreshold = 50;
        config.slidingWindowSize = 10;
        config.minimumNumberOfCalls = 5;
        config.waitDurationInOpenState = 1; // 1 秒
        config.permittedNumberOfCallsInHalfOpenState = 3;
        config.slowCallDurationThreshold = 1000;
        config.slowCallRateThreshold = 100;
    }

    private BackendCircuitBreaker createCircuitBreaker() {
        return new BackendCircuitBreaker(config, null, ProxyMetrics.noop(), new AuditLogger());
    }

    // ========== 初始化测试 ==========

    @Test
    void testInitialize_CreatesInstance() {
        BackendCircuitBreaker cb = createCircuitBreaker();
        assertNotNull(cb);
    }

    // ========== CLOSED 状态测试 ==========

    @Test
    void testAllowRequest_ClosedState_ReturnsTrue() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        assertTrue(cb.allowRequest());
        assertEquals(BackendCircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    void testRecordSuccess_UpdatesStats() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 需要先获取许可
        cb.allowRequest();
        cb.recordSuccess(100);
        cb.allowRequest();
        cb.recordSuccess(200);

        BackendCircuitBreaker.CircuitBreakerStats stats = cb.getStats();
        assertEquals(2, stats.totalCalls);
        assertEquals(2, stats.successfulCalls);
        assertEquals(0, stats.failedCalls);
    }

    @Test
    void testRecordFailure_BelowThreshold_StaysClosed() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 4 次失败，低于 minimumNumberOfCalls=5
        for (int i = 0; i < 4; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }

        assertEquals(BackendCircuitBreaker.State.CLOSED, cb.getState());
    }

    // ========== CLOSED -> OPEN 转换测试 ==========

    @Test
    void testTransitionToOpen_WhenFailureRateExceeded() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 需要 >= 5 次调用且 >= 50% 失败率
        // 2 成功 + 4 失败 = 6 次调用，约 67% 失败率
        cb.allowRequest();
        cb.recordSuccess(100);
        cb.allowRequest();
        cb.recordSuccess(100);
        cb.allowRequest();
        cb.recordFailure(100);
        cb.allowRequest();
        cb.recordFailure(100);
        cb.allowRequest();
        cb.recordFailure(100);
        cb.allowRequest();
        cb.recordFailure(100);

        assertEquals(BackendCircuitBreaker.State.OPEN, cb.getState());
    }

    @Test
    void testTransitionToOpen_HighFailureRate() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 5 次失败 = 100% 失败率
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }

        assertEquals(BackendCircuitBreaker.State.OPEN, cb.getState());
    }

    // ========== OPEN 状态测试 ==========

    @Test
    void testAllowRequest_OpenState_ReturnsFalse() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 触发熔断
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }

        assertEquals(BackendCircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowRequest());
    }

    @Test
    void testOpenState_IncrementsRejectedCalls() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 触发熔断
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }

        // 尝试请求
        cb.allowRequest();
        cb.allowRequest();
        cb.allowRequest();

        BackendCircuitBreaker.CircuitBreakerStats stats = cb.getStats();
        assertEquals(3, stats.rejectedCalls);
    }

    // ========== OPEN -> HALF_OPEN 转换测试 ==========

    @Test
    void testTransitionToHalfOpen_AfterWaitDuration() throws InterruptedException {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 触发熔断
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }
        assertEquals(BackendCircuitBreaker.State.OPEN, cb.getState());

        // 等待超过 waitDurationInOpenState (1 秒)
        // Resilience4j 自动转换到 HALF_OPEN
        Thread.sleep(1200);

        // Resilience4j 自动转换，状态应该是 HALF_OPEN
        assertEquals(BackendCircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.allowRequest());
    }

    // ========== HALF_OPEN 状态测试 ==========

    @Test
    void testHalfOpen_AllowsLimitedRequests() throws InterruptedException {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 触发熔断并等待进入 HALF_OPEN
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }
        Thread.sleep(1200);

        // Resilience4j 自动转换到 HALF_OPEN
        assertEquals(BackendCircuitBreaker.State.HALF_OPEN, cb.getState());

        // permittedNumberOfCallsInHalfOpenState = 3
        assertTrue(cb.allowRequest()); // 第1次
        assertTrue(cb.allowRequest()); // 第2次
        assertTrue(cb.allowRequest()); // 第3次
        // 第4次应该被拒绝
        assertFalse(cb.allowRequest());
    }

    // ========== HALF_OPEN -> CLOSED 转换测试 ==========

    @Test
    void testTransitionToClosed_WhenSuccessRateHigh() throws InterruptedException {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 触发熔断并等待进入 HALF_OPEN
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }
        Thread.sleep(1200);

        // 在半开状态记录成功调用
        for (int i = 0; i < 3; i++) {
            cb.allowRequest();
            cb.recordSuccess(100);
        }

        assertEquals(BackendCircuitBreaker.State.CLOSED, cb.getState());
    }

    // ========== HALF_OPEN -> OPEN 转换测试 ==========

    @Test
    void testTransitionToOpen_WhenFailInHalfOpen() throws InterruptedException {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 触发熔断并等待进入 HALF_OPEN
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }
        Thread.sleep(1200);
        assertEquals(BackendCircuitBreaker.State.HALF_OPEN, cb.getState());

        // 在半开状态记录失败 (需要达到 permittedNumberOfCallsInHalfOpenState 次)
        // Resilience4j 需要收集足够的调用才能判断
        for (int i = 0; i < 3; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }

        assertEquals(BackendCircuitBreaker.State.OPEN, cb.getState());
    }

    // ========== reset 测试 ==========

    @Test
    void testReset_ResetsStateToClosed() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 触发熔断
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }
        assertEquals(BackendCircuitBreaker.State.OPEN, cb.getState());

        // 重置
        cb.reset();

        assertEquals(BackendCircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.allowRequest());
    }

    // ========== 统计信息测试 ==========

    @Test
    void testGetStats_ReturnsCorrectValues() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        cb.allowRequest();
        cb.recordSuccess(100);
        cb.allowRequest();
        cb.recordSuccess(200);
        cb.allowRequest();
        cb.recordFailure(100);

        BackendCircuitBreaker.CircuitBreakerStats stats = cb.getStats();

        assertEquals(BackendCircuitBreaker.State.CLOSED, stats.state);
        assertEquals(3, stats.totalCalls);
        assertEquals(2, stats.successfulCalls);
        assertEquals(1, stats.failedCalls);
        assertEquals(0, stats.rejectedCalls);
    }

    // ========== 滑动窗口测试 ==========

    @Test
    void testSlidingWindow_RemovesOldResults() {
        // 窗口大小为 10
        BackendCircuitBreaker cb = createCircuitBreaker();

        // 添加 10 次成功
        for (int i = 0; i < 10; i++) {
            cb.allowRequest();
            cb.recordSuccess(100);
        }

        // 再添加 5 次失败，触发熔断
        // Resilience4j 使用 Ring Buffer，旧的记录会被覆盖
        for (int i = 0; i < 5; i++) {
            cb.allowRequest();
            cb.recordFailure(100);
        }

        // 窗口中应该有 5 成功 + 5 失败 = 50% 失败率，触发熔断
        assertEquals(BackendCircuitBreaker.State.OPEN, cb.getState());
    }

    // ========== Resilience4j 特有测试 ==========

    @Test
    void testGetResilience4jCircuitBreaker_NotNull() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        assertNotNull(cb.getResilience4jCircuitBreaker());
    }

    @Test
    void testResilience4jMetrics_Available() {
        BackendCircuitBreaker cb = createCircuitBreaker();

        io.github.resilience4j.circuitbreaker.CircuitBreaker r4jCb = cb.getResilience4jCircuitBreaker();
        assertNotNull(r4jCb.getMetrics());
        assertEquals(0, r4jCb.getMetrics().getNumberOfBufferedCalls());
    }
}
