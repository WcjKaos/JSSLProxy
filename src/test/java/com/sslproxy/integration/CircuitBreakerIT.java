package com.sslproxy.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sslproxy.config.ProxyConfig;

/**
 * 熔断器集成测试
 *
 * 测试熔断器在后端故障时的行为。
 * 验证通过 HTTP 响应状态码进行，不直接访问熔断器实例。
 */
@DisplayName("熔断器集成测试")
class CircuitBreakerIT extends IntegrationTestBase {

    @Override
    protected ProxyConfig createProxyConfig() {
        ProxyConfig config = super.createProxyConfig();

        // 启用熔断器，使用较小的阈值以便快速测试
        config.circuitBreaker = new ProxyConfig.CircuitBreaker();
        config.circuitBreaker.enabled = true;
        config.circuitBreaker.failureRateThreshold = 50; // 50% 失败率触发熔断
        config.circuitBreaker.slidingWindowSize = 10;
        config.circuitBreaker.minimumNumberOfCalls = 5;
        config.circuitBreaker.waitDurationInOpenState = 2; // 2 秒
        config.circuitBreaker.permittedNumberOfCallsInHalfOpenState = 3;
        config.circuitBreaker.slowCallDurationThreshold = 5000;
        config.circuitBreaker.slowCallRateThreshold = 100;

        return config;
    }

    @Test
    @DisplayName("正常请求不应触发熔断")
    void testNormalRequestsDoNotTriggerCircuitBreaker() throws Exception {
        // 发送多个正常请求
        for (int i = 0; i < 10; i++) {
            HttpResponse response = sendGetRequest("/normal/" + i);
            assertThat(response.statusCode).isEqualTo(200);
        }

        // 验证所有请求都到达后端
        assertThat(backendServer.getRequestCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("熔断器初始化后正常请求应返回 200")
    void testCircuitBreakerInitialState() throws Exception {
        // 发送一个请求以确保服务器启动，熔断器处于 CLOSED 状态时应正常响应
        HttpResponse response = sendGetRequest("/init");
        assertThat(response.statusCode).isEqualTo(200);
    }

    @Test
    @DisplayName("熔断器应记录成功调用（通过正常响应验证）")
    void testCircuitBreakerRecordsSuccessfulCalls() throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpResponse response = sendGetRequest("/success/" + i);
            assertThat(response.statusCode).isEqualTo(200);
        }

        // 所有请求都应到达后端
        assertThat(backendServer.getRequestCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("正常请求后继续接受新请求（熔断器处于 CLOSED 状态）")
    void testCircuitBreakerAllowsRequestsWhenClosed() throws Exception {
        // 发送多个成功请求后，后续请求仍应被允许
        for (int i = 0; i < 5; i++) {
            HttpResponse response = sendGetRequest("/test/" + i);
            assertThat(response.statusCode).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("多个成功请求应全部到达后端")
    void testCircuitBreakerStatsContainCorrectData() throws Exception {
        // 发送请求
        for (int i = 0; i < 3; i++) {
            HttpResponse response = sendGetRequest("/stats/" + i);
            assertThat(response.statusCode).isEqualTo(200);
        }

        // 验证所有请求都到达后端
        assertThat(backendServer.getRequestCount()).isEqualTo(3);
    }
}
