package com.sslproxy.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ProxyConfig 单元测试
 */
class ProxyConfigTest {

    private ProxyConfig config;

    @BeforeEach
    void setUp() {
        config = new ProxyConfig();
    }

    // ========== Server 配置测试 ==========

    @Test
    void testServerConfigDefaults() {
        ProxyConfig.Server server = new ProxyConfig.Server();
        assertNull(server.host);
        assertEquals(0, server.port);
    }

    // ========== Target 配置测试 ==========

    @Test
    void testTargetConfigDefaults() {
        ProxyConfig.Target target = new ProxyConfig.Target();
        assertNull(target.host);
        assertEquals(0, target.port);
        assertNull(target.protocol);
    }

    // ========== SSL 配置测试 ==========

    @Test
    void testSslConfigDefaults() {
        ProxyConfig.Ssl ssl = new ProxyConfig.Ssl();
        assertNull(ssl.keystore);
        assertNull(ssl.truststore);
        assertNull(ssl.clientAuth);
        assertNull(ssl.protocols);
        assertNull(ssl.ciphers);
        assertEquals("SYSTEM", ssl.backendTrustStrategy);
    }

    // ========== KeystoreConfig 测试 ==========

    @Test
    void testKeystoreConfigDefaults() {
        ProxyConfig.KeystoreConfig keystoreConfig = new ProxyConfig.KeystoreConfig();
        assertNull(keystoreConfig.path);
        assertNull(keystoreConfig.certFile);
        assertNull(keystoreConfig.keyFile);
        assertNull(keystoreConfig.password);
        assertNull(keystoreConfig.type);
    }

    // ========== Connection 配置测试 ==========

    @Test
    void testConnectionConfigDefaults() {
        ProxyConfig.Connection connection = new ProxyConfig.Connection();
        assertEquals(0, connection.timeout);
        assertFalse(connection.keepalive);
    }

    // ========== Performance 配置测试 ==========

    @Test
    void testPerformanceConfigDefaults() {
        ProxyConfig.Performance performance = new ProxyConfig.Performance();
        assertEquals(1024, performance.backlog);
        assertEquals(0, performance.workerThreads);
        assertNull(performance.backendPool);
    }

    @Test
    void testBackendPoolConfigDefaults() {
        ProxyConfig.BackendPoolConfig poolConfig = new ProxyConfig.BackendPoolConfig();
        assertTrue(poolConfig.enabled);
        assertEquals(100, poolConfig.maxConnections);
        assertEquals(5000, poolConfig.acquireTimeout);
        assertEquals(60000, poolConfig.maxIdleTime);
    }

    // ========== Management 配置测试 ==========

    @Test
    void testManagementConfigDefaults() {
        ProxyConfig.Management management = new ProxyConfig.Management();
        assertFalse(management.enabled);
        assertEquals("127.0.0.1", management.host);
        assertEquals(9090, management.port);
        assertTrue(management.authEnabled);
        assertNull(management.authToken);
    }

    // ========== Metrics 配置测试 ==========

    @Test
    void testMetricsConfigDefaults() {
        ProxyConfig.Metrics metrics = new ProxyConfig.Metrics();
        assertTrue(metrics.enabled);
        assertTrue(metrics.includeJvmMetrics);
        assertNull(metrics.histogramBuckets);
    }

    // ========== RateLimit 配置测试 ==========

    @Test
    void testRateLimitConfigDefaults() {
        ProxyConfig.RateLimit rateLimit = new ProxyConfig.RateLimit();
        assertFalse(rateLimit.enabled);
        assertEquals(5000, rateLimit.globalRps);
        assertEquals(500, rateLimit.globalBurstCapacity);
        assertEquals(200, rateLimit.clientRps);
        assertEquals(50, rateLimit.clientBurstCapacity);
    }

    // ========== Shutdown 配置测试 ==========

    @Test
    void testShutdownConfigDefaults() {
        ProxyConfig.Shutdown shutdown = new ProxyConfig.Shutdown();
        assertEquals(60, shutdown.timeoutSeconds);
        assertTrue(shutdown.awaitRequests);
    }

    // ========== getEnvOrDefault 测试 ==========

    @Test
    void testGetEnvOrDefaultWithExistingEnv() {
        // PATH 环境变量通常存在
        String path = ProxyConfig.getEnvOrDefault("PATH", "default");
        assertNotEquals("default", path);
    }

    @Test
    void testGetEnvOrDefaultWithNonExistingEnv() {
        String result = ProxyConfig.getEnvOrDefault("VERY_UNLIKELY_ENV_VAR_12345", "fallback");
        assertEquals("fallback", result);
    }

    // ========== validate 方法测试 ==========

    @Test
    void testValidateWithMissingServer() {
        config.server = null;

        assertThrows(NullPointerException.class, () -> config.validate());
    }

    @Test
    void testValidateWithInvalidServerPort() {
        config.server = new ProxyConfig.Server();
        config.server.host = "localhost";
        config.server.port = 70000; // 超出范围

        assertThrows(IllegalArgumentException.class, () -> config.validate());
    }

    @Test
    void testValidateWithMissingServerHost() {
        config.server = new ProxyConfig.Server();
        config.server.port = 8080;
        config.server.host = "";

        assertThrows(IllegalArgumentException.class, () -> config.validate());
    }

    @Test
    void testValidateWithMissingTarget() {
        config.server = createValidServer();
        config.target = null;

        assertThrows(NullPointerException.class, () -> config.validate());
    }

    @Test
    void testValidateWithInvalidTargetPort() {
        config.server = createValidServer();
        config.target = new ProxyConfig.Target();
        config.target.host = "backend";
        config.target.port = -1;
        config.target.protocol = "https";

        assertThrows(IllegalArgumentException.class, () -> config.validate());
    }

    @Test
    void testValidateWithInvalidProtocol() {
        config.server = createValidServer();
        config.target = new ProxyConfig.Target();
        config.target.host = "backend";
        config.target.port = 443;
        config.target.protocol = "ftp"; // 无效协议

        assertThrows(IllegalArgumentException.class, () -> config.validate());
    }

    @Test
    void testValidateWithMissingSsl() {
        config.server = createValidServer();
        config.target = createValidTarget();
        config.ssl = null;

        assertThrows(NullPointerException.class, () -> config.validate());
    }

    @Test
    void testValidateWithMissingKeystore() {
        config.server = createValidServer();
        config.target = createValidTarget();
        config.ssl = new ProxyConfig.Ssl();
        config.ssl.keystore = null;

        assertThrows(NullPointerException.class, () -> config.validate());
    }

    @Test
    void testValidateWithMissingKeystorePath() {
        config.server = createValidServer();
        config.target = createValidTarget();
        config.ssl = new ProxyConfig.Ssl();
        config.ssl.keystore = new ProxyConfig.KeystoreConfig();
        config.ssl.truststore = new ProxyConfig.KeystoreConfig();
        config.ssl.truststore.path = "truststore.p12";
        // 没有设置 keystore.path 或 certFile+keyFile

        assertThrows(IllegalArgumentException.class, () -> config.validate());
    }

    @Test
    void testValidateWithPemFormat() {
        config.server = createValidServer();
        config.target = createValidTarget();
        config.ssl = new ProxyConfig.Ssl();
        config.ssl.keystore = new ProxyConfig.KeystoreConfig();
        config.ssl.keystore.certFile = "classpath:server.crt";  // 使用 classpath: 前缀跳过文件存在性检查
        config.ssl.keystore.keyFile = "classpath:server.key";    // 使用 classpath: 前缀跳过文件存在性检查
        config.ssl.truststore = new ProxyConfig.KeystoreConfig();
        config.ssl.truststore.path = "classpath:truststore.p12"; // 使用 classpath: 前缀跳过文件存在性检查

        // PEM 格式应该通过验证
        assertDoesNotThrow(() -> config.validate());
    }

    @Test
    void testValidateWithInvalidClientAuth() {
        config.server = createValidServer();
        config.target = createValidTarget();
        config.ssl = createValidSsl();
        config.ssl.clientAuth = "INVALID";

        assertThrows(IllegalArgumentException.class, () -> config.validate());
    }

    @Test
    void testValidateWithValidClientAuthModes() {
        config.server = createValidServer();
        config.target = createValidTarget();

        // 测试 NONE
        config.ssl = createValidSsl();
        config.ssl.clientAuth = "none"; // 小写
        assertDoesNotThrow(() -> config.validate());
        assertEquals("NONE", config.ssl.clientAuth); // 应该被标准化为大写

        // 测试 OPTIONAL
        config.ssl = createValidSsl();
        config.ssl.clientAuth = "Optional";
        assertDoesNotThrow(() -> config.validate());
        assertEquals("OPTIONAL", config.ssl.clientAuth);

        // 测试 REQUIRED
        config.ssl = createValidSsl();
        config.ssl.clientAuth = "REQUIRED";
        assertDoesNotThrow(() -> config.validate());
    }

    @Test
    void testValidateWithValidBackendTrustStrategyModes() {
        config.server = createValidServer();
        config.target = createValidTarget();

        config.ssl = createValidSsl();
        config.ssl.backendTrustStrategy = "system";
        assertDoesNotThrow(() -> config.validate());
        assertEquals("SYSTEM", config.ssl.backendTrustStrategy);

        config.ssl = createValidSsl();
        config.ssl.backendTrustStrategy = "CUSTOM";
        assertDoesNotThrow(() -> config.validate());

        config.ssl = createValidSsl();
        config.ssl.backendTrustStrategy = "Both";
        assertDoesNotThrow(() -> config.validate());
        assertEquals("BOTH", config.ssl.backendTrustStrategy);
    }

    @Test
    void testValidateWithInvalidBackendTrustStrategy() {
        config.server = createValidServer();
        config.target = createValidTarget();
        config.ssl = createValidSsl();
        config.ssl.backendTrustStrategy = "INVALID";

        assertThrows(IllegalArgumentException.class, () -> config.validate());
    }

    @Test
    void testValidateDefaultsClientAuthToRequired() {
        config.server = createValidServer();
        config.target = createValidTarget();
        config.ssl = createValidSsl();
        config.ssl.clientAuth = null;

        assertDoesNotThrow(() -> config.validate());
        assertEquals("REQUIRED", config.ssl.clientAuth);
    }

    // ========== loadFromFile 测试 ==========

    @Test
    void testLoadFromFile(@TempDir Path tempDir) throws IOException {
        String yaml = """
            server:
              host: localhost
              port: 18443
            target:
              host: backend.example.com
              port: 443
              protocol: https
            ssl:
              keystore:
                path: classpath:server.p12
                password: changeit
                type: PKCS12
              truststore:
                path: classpath:truststore.p12
                password: changeit
                type: PKCS12
              clientAuth: REQUIRED
              backendTrustStrategy: BOTH
              protocols:
                - TLSv1.3
                - TLSv1.2
            """;

        Path configFile = tempDir.resolve("test-config.yml");
        Files.writeString(configFile, yaml);

        ProxyConfig loadedConfig = ProxyConfig.loadFromFile(configFile.toString());

        assertNotNull(loadedConfig);
        assertEquals("localhost", loadedConfig.server.host);
        assertEquals(18443, loadedConfig.server.port);
        assertEquals("backend.example.com", loadedConfig.target.host);
        assertEquals(443, loadedConfig.target.port);
        assertEquals("https", loadedConfig.target.protocol);
        assertEquals("REQUIRED", loadedConfig.ssl.clientAuth);
        assertEquals("BOTH", loadedConfig.ssl.backendTrustStrategy);
    }

    @Test
    void testLoadFromFileNotFound() {
        assertThrows(RuntimeException.class,
            () -> ProxyConfig.loadFromFile("/non/existent/path.yml"));
    }

    // ========== 辅助方法 ==========

    private ProxyConfig.Server createValidServer() {
        ProxyConfig.Server server = new ProxyConfig.Server();
        server.host = "0.0.0.0";
        server.port = 18443;
        return server;
    }

    private ProxyConfig.Target createValidTarget() {
        ProxyConfig.Target target = new ProxyConfig.Target();
        target.host = "backend.example.com";
        target.port = 443;
        target.protocol = "https";
        return target;
    }

    private ProxyConfig.Ssl createValidSsl() {
        ProxyConfig.Ssl ssl = new ProxyConfig.Ssl();
        ssl.keystore = new ProxyConfig.KeystoreConfig();
        ssl.keystore.path = "classpath:server.p12";  // 使用 classpath: 前缀跳过文件存在性检查
        ssl.keystore.password = "changeit";
        ssl.keystore.type = "PKCS12";
        ssl.truststore = new ProxyConfig.KeystoreConfig();
        ssl.truststore.path = "classpath:truststore.p12";  // 使用 classpath: 前缀跳过文件存在性检查
        ssl.truststore.password = "changeit";
        ssl.truststore.type = "PKCS12";
        ssl.clientAuth = "REQUIRED";
        ssl.protocols = Arrays.asList("TLSv1.3", "TLSv1.2");
        return ssl;
    }

    // ========== computeConfigHash 测试 ==========

    @Test
    void should_returnDeterministicHash_when_sameConfig() {
        ProxyConfig config1 = buildMinimalConfig();
        ProxyConfig config2 = buildMinimalConfig();

        assertThat(config1.computeConfigHash()).isEqualTo(config2.computeConfigHash());
    }

    @Test
    void should_returnDifferentHash_when_configDiffers() {
        ProxyConfig config1 = buildMinimalConfig();
        ProxyConfig config2 = buildMinimalConfig();
        config2.server.port = 9443;

        assertThat(config1.computeConfigHash()).isNotEqualTo(config2.computeConfigHash());
    }

    @Test
    void should_excludePasswords_fromHash() {
        ProxyConfig config1 = buildMinimalConfig();
        config1.ssl.keystore = new ProxyConfig.KeystoreConfig();
        config1.ssl.keystore.password = "password-one";
        config1.ssl.truststore = new ProxyConfig.KeystoreConfig();
        config1.ssl.truststore.password = "trustpass-one";
        config1.management.authToken = "token-aaa";

        ProxyConfig config2 = buildMinimalConfig();
        config2.ssl.keystore = new ProxyConfig.KeystoreConfig();
        config2.ssl.keystore.password = "password-two";
        config2.ssl.truststore = new ProxyConfig.KeystoreConfig();
        config2.ssl.truststore.password = "trustpass-two";
        config2.management.authToken = "token-bbb";

        assertThat(config1.computeConfigHash()).isEqualTo(config2.computeConfigHash());
    }

    @Test
    void should_returnEightCharHexString() {
        ProxyConfig cfg = buildMinimalConfig();

        String hash = cfg.computeConfigHash();

        assertThat(hash).hasSize(8);
        assertThat(hash).matches("[0-9a-f]{8}");
    }

    private ProxyConfig buildMinimalConfig() {
        ProxyConfig c = new ProxyConfig();
        c.server = new ProxyConfig.Server();
        c.server.host = "0.0.0.0";
        c.server.port = 8443;
        c.target = new ProxyConfig.Target();
        c.target.host = "localhost";
        c.target.port = 8080;
        c.target.protocol = "https";
        c.target.strategy = "ROUND_ROBIN";
        c.ssl = new ProxyConfig.Ssl();
        c.ssl.clientAuth = "REQUIRED";
        c.ssl.protocols = java.util.Arrays.asList("TLSv1.3", "TLSv1.2");
        c.ssl.handshakeTimeout = 10000;
        c.ssl.backendTrustStrategy = "SYSTEM";
        c.ssl.minimumRsaKeyBits = 2048;
        c.performance = new ProxyConfig.Performance();
        c.performance.maxClientConnections = 10000;
        c.performance.maxRequestSize = 10485760;
        c.rateLimit = new ProxyConfig.RateLimit();
        c.management = new ProxyConfig.Management();
        return c;
    }
}
