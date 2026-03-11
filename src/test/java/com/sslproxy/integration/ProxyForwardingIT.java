package com.sslproxy.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 代理转发集成测试
 *
 * 测试代理服务器正确转发 HTTP 请求到后端服务器
 */
@DisplayName("代理转发集成测试")
class ProxyForwardingIT extends IntegrationTestBase {

    @Test
    @DisplayName("GET 请求应被正确转发")
    void testGetRequestForwarding() throws Exception {
        HttpResponse response = sendGetRequest("/api/test");

        assertThat(response.statusCode).isEqualTo(200);
        assertThat(response.body).contains("OK");
        assertThat(response.body).contains("Method: GET");
        assertThat(response.body).contains("Path: /api/test");
    }

    @Test
    @DisplayName("POST 请求应被正确转发")
    void testPostRequestForwarding() throws Exception {
        HttpResponse response = sendPostRequest("/api/data", "test-body");

        assertThat(response.statusCode).isEqualTo(200);
        assertThat(response.body).contains("Method: POST");
        assertThat(response.body).contains("Path: /api/data");
    }

    @Test
    @DisplayName("后端返回非 200 状态码应正确传递")
    void testNon200StatusCodePassthrough() throws Exception {
        backendServer.withResponseStatus(404);

        HttpResponse response = sendGetRequest("/not-found");

        assertThat(response.statusCode).isEqualTo(404);
    }

    @Test
    @DisplayName("后端返回 500 错误应正确传递")
    void testServerErrorPassthrough() throws Exception {
        backendServer.withResponseStatus(500);

        HttpResponse response = sendGetRequest("/error");

        assertThat(response.statusCode).isEqualTo(500);
    }

    @Test
    @DisplayName("多个请求应被正确计数")
    void testMultipleRequests() throws Exception {
        sendGetRequest("/request1");
        sendGetRequest("/request2");
        sendGetRequest("/request3");

        assertThat(backendServer.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("自定义响应体应正确传递")
    void testCustomResponseBody() throws Exception {
        backendServer.withResponseBody("Custom Response Body");

        HttpResponse response = sendGetRequest("/custom");

        assertThat(response.body).contains("Custom Response Body");
    }

    @Test
    @DisplayName("请求路径应正确记录")
    void testRequestPathRecording() throws Exception {
        sendGetRequest("/api/users/123");

        assertThat(backendServer.getLastRequestPath()).isEqualTo("/api/users/123");
        assertThat(backendServer.getLastRequestMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("带查询参数的请求应正确转发")
    void testRequestWithQueryParams() throws Exception {
        HttpResponse response = sendGetRequest("/search?q=test&page=1");

        assertThat(response.statusCode).isEqualTo(200);
        assertThat(backendServer.getLastRequestPath()).isEqualTo("/search?q=test&page=1");
    }

    @Test
    @DisplayName("重置后端配置后应恢复默认行为")
    void testResetConfig() throws Exception {
        backendServer.withResponseStatus(500);
        HttpResponse errorResponse = sendGetRequest("/error");
        assertThat(errorResponse.statusCode).isEqualTo(500);

        backendServer.resetConfig();
        HttpResponse normalResponse = sendGetRequest("/normal");
        assertThat(normalResponse.statusCode).isEqualTo(200);
    }
}
