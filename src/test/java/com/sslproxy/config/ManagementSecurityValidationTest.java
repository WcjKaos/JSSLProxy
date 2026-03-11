package com.sslproxy.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * 管理端口安全验证测试
 * <p>
 * 测试管理端口配置的安全验证逻辑，确保不安全的配置在启动时被拒绝
 * </p>
 */
public class ManagementSecurityValidationTest {

    /**
     * 测试：管理端口外部暴露但未启用鉴权 - 应抛出异常
     */
    @Test
    public void testManagementExposedWithoutAuth_ThrowsException() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = true;
        config.management.host = "0.0.0.0"; // 外部暴露
        config.management.authEnabled = false; // 未启用鉴权
        config.management.authToken = "test-token";

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            config::validate
        );

        assertTrue(exception.getMessage().contains("authEnabled=false"));
        assertTrue(exception.getMessage().contains("security risk"));
    }

    /**
     * 测试：管理端口外部暴露但未配置 Token - 应抛出异常
     */
    @Test
    public void testManagementExposedWithoutToken_ThrowsException() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = true;
        config.management.host = "0.0.0.0"; // 外部暴露
        config.management.authEnabled = true; // 启用鉴权
        config.management.authToken = null; // 未配置 token

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            config::validate
        );

        assertTrue(exception.getMessage().contains("authToken is not configured"));
    }

    /**
     * 测试：管理端口外部暴露但 Token 为空字符串 - 应抛出异常
     */
    @Test
    public void testManagementExposedWithBlankToken_ThrowsException() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = true;
        config.management.host = "192.168.1.100"; // 外部暴露
        config.management.authEnabled = true;
        config.management.authToken = "   "; // 空白字符串

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            config::validate
        );

        assertTrue(exception.getMessage().contains("authToken is not configured"));
    }

    /**
     * 测试：管理端口绑定到 localhost 且未启用鉴权 - 应允许
     */
    @Test
    public void testManagementLocalhostWithoutAuth_Allowed() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = true;
        config.management.host = "127.0.0.1"; // localhost
        config.management.authEnabled = false; // 未启用鉴权
        config.management.authToken = null;

        // 不应抛出异常
        assertDoesNotThrow(config::validate);
    }

    /**
     * 测试：管理端口绑定到 localhost（使用 "localhost" 字符串）且未启用鉴权 - 应允许
     */
    @Test
    public void testManagementLocalhostStringWithoutAuth_Allowed() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = true;
        config.management.host = "localhost"; // localhost 字符串
        config.management.authEnabled = false;
        config.management.authToken = null;

        assertDoesNotThrow(config::validate);
    }

    /**
     * 测试：管理端口绑定到 IPv6 localhost 且未启用鉴权 - 应允许
     */
    @Test
    public void testManagementIpv6LocalhostWithoutAuth_Allowed() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = true;
        config.management.host = "::1"; // IPv6 localhost
        config.management.authEnabled = false;
        config.management.authToken = null;

        assertDoesNotThrow(config::validate);
    }

    /**
     * 测试：管理端口外部暴露且正确配置鉴权和 Token - 应允许
     */
    @Test
    public void testManagementExposedWithAuthAndToken_Allowed() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = true;
        config.management.host = "0.0.0.0"; // 外部暴露
        config.management.authEnabled = true; // 启用鉴权
        config.management.authToken = "secure-token-12345"; // 配置 token

        // 不应抛出异常
        assertDoesNotThrow(config::validate);
    }

    /**
     * 测试：管理端口外部暴露到特定 IP 且正确配置 - 应允许
     */
    @Test
    public void testManagementSpecificIpWithAuthAndToken_Allowed() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = true;
        config.management.host = "192.168.1.100"; // 特定 IP
        config.management.authEnabled = true;
        config.management.authToken = "secure-token-12345";

        assertDoesNotThrow(config::validate);
    }

    /**
     * 测试：管理端口未启用 - 不进行验证
     */
    @Test
    public void testManagementDisabled_NoValidation() {
        ProxyConfig config = createMinimalConfig();
        config.management.enabled = false; // 未启用
        config.management.host = "0.0.0.0";
        config.management.authEnabled = false;
        config.management.authToken = null;

        // 不应抛出异常（因为管理端口未启用）
        assertDoesNotThrow(config::validate);
    }

    /**
     * 测试：管理端口为 null - 不进行验证
     */
    @Test
    public void testManagementNull_NoValidation() {
        ProxyConfig config = createMinimalConfig();
        config.management = null;

        // 不应抛出异常
        assertDoesNotThrow(config::validate);
    }

    // ========== 辅助方法 ==========

    /**
     * 创建最小化的有效配置（用于测试）
     */
    private ProxyConfig createMinimalConfig() {
        ProxyConfig config = new ProxyConfig();

        // 服务器配置
        config.server = new ProxyConfig.Server();
        config.server.host = "0.0.0.0";
        config.server.port = 8443;

        // 目标后端配置
        config.target = new ProxyConfig.Target();
        config.target.host = "localhost";
        config.target.port = 8080;
        config.target.protocol = "http";

        // SSL 配置
        config.ssl = new ProxyConfig.Ssl();

        // Keystore 配置
        config.ssl.keystore = new ProxyConfig.KeystoreConfig();
        config.ssl.keystore.type = "PKCS12";
        config.ssl.keystore.path = "classpath:test-keystore.p12";
        config.ssl.keystore.password = "changeit";

        // Truststore 配置
        config.ssl.truststore = new ProxyConfig.KeystoreConfig();
        config.ssl.truststore.type = "PKCS12";
        config.ssl.truststore.path = "classpath:test-truststore.p12";
        config.ssl.truststore.password = "changeit";

        // 连接配置
        config.connection = new ProxyConfig.Connection();
        config.connection.timeout = 30000;
        config.connection.keepalive = true;

        // 管理端口配置
        config.management = new ProxyConfig.Management();
        config.management.enabled = false; // 默认禁用

        // 指标配置
        config.metrics = new ProxyConfig.Metrics();
        config.metrics.enabled = false;

        // 性能配置
        config.performance = new ProxyConfig.Performance();
        config.performance.backlog = 1024;
        config.performance.workerThreads = 4;

        // 限流配置
        config.rateLimit = new ProxyConfig.RateLimit();
        config.rateLimit.enabled = false;

        return config;
    }
}
