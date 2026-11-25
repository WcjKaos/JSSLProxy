package com.sslproxy.integration;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.server.ProxyServer;

import io.netty.util.ResourceLeakDetector;

import static org.awaitility.Awaitility.await;

/**
 * 集成测试基类
 *
 * 提供代理服务器和后端服务器的启动/停止，以及 HTTP 客户端
 */
public abstract class IntegrationTestBase {

    protected static TestCertificates testCerts;
    protected static Path serverKeystorePath;
    protected static Path clientKeystorePath;
    protected static Path truststorePath;

    protected ProxyServer proxyServer;
    protected Thread proxyServerThread;
    protected TestBackendServer backendServer;
    protected CloseableHttpClient httpClient;

    protected int proxyPort;
    protected int backendPort;

    @BeforeAll
    static void setUpCertificates() throws Exception {
        // 启用 Netty ByteBuf 泄漏检测（测试环境使用 PARANOID 级别）
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);

        testCerts = new TestCertificates();
        testCerts.initializeCA();
        serverKeystorePath = testCerts.generateServerKeyStore("localhost");
        clientKeystorePath = testCerts.generateClientKeyStore("test-client");
        truststorePath = testCerts.generateTrustStore();
    }

    @AfterAll
    static void tearDownCertificates() {
        if (testCerts != null) {
            testCerts.cleanup();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // 分配随机端口
        proxyPort = findAvailablePort();
        backendPort = findAvailablePort();

        // 创建并启动后端服务器
        backendServer = createBackendServer();
        backendServer.start();

        // 创建代理配置
        ProxyConfig config = createProxyConfig();

        // 创建代理服务器并在后台线程启动
        proxyServer = new ProxyServer(config);
        AtomicReference<Exception> startupException = new AtomicReference<>();
        CountDownLatch startupLatch = new CountDownLatch(1);

        proxyServerThread = new Thread(() -> {
            try {
                // 信号服务器即将启动
                startupLatch.countDown();
                proxyServer.start();
            } catch (Exception e) {
                startupException.set(e);
            }
        }, "proxy-server-thread");
        proxyServerThread.setDaemon(true);
        proxyServerThread.start();

        // 等待服务器线程开始
        startupLatch.await(5, TimeUnit.SECONDS);

        // 等待服务器实际启动
        waitForServerStart();

        // 检查启动异常
        if (startupException.get() != null) {
            throw startupException.get();
        }

        // 创建 HTTP 客户端
        httpClient = createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpClient != null) {
            httpClient.close();
        }
        if (proxyServer != null) {
            proxyServer.shutdown();
        }
        if (proxyServerThread != null) {
            proxyServerThread.interrupt();
            proxyServerThread.join(2000);
        }
        if (backendServer != null) {
            backendServer.stop();
        }

        // No singleton reset needed — instances are created per server lifecycle
    }

    /**
     * 等待代理服务器启动
     */
    protected void waitForServerStart() throws Exception {
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> proxyServer != null && proxyServer.isRunning());
    }

    /**
     * 创建后端服务器，子类可以覆盖
     */
    protected TestBackendServer createBackendServer() {
        return new TestBackendServer(backendPort);
    }

    /**
     * 创建代理配置，子类可以覆盖
     */
    protected ProxyConfig createProxyConfig() {
        ProxyConfig config = new ProxyConfig();

        // 服务器配置
        config.server = new ProxyConfig.Server();
        config.server.host = "0.0.0.0";
        config.server.port = proxyPort;

        // 目标后端配置
        config.target = new ProxyConfig.Target();
        config.target.host = "localhost";
        config.target.port = backendPort;
        config.target.protocol = "http";

        // SSL 配置 - 使用测试证书
        config.ssl = new ProxyConfig.Ssl();
        config.ssl.keystore = new ProxyConfig.KeystoreConfig();
        config.ssl.keystore.path = serverKeystorePath.toString();
        config.ssl.keystore.password = TestCertificates.getDefaultPasswordString();
        config.ssl.keystore.type = "PKCS12";

        config.ssl.truststore = new ProxyConfig.KeystoreConfig();
        config.ssl.truststore.path = truststorePath.toString();
        config.ssl.truststore.password = TestCertificates.getDefaultPasswordString();
        config.ssl.truststore.type = "PKCS12";

        config.ssl.clientAuth = "NONE";
        config.ssl.protocols = Arrays.asList("TLSv1.3", "TLSv1.2");

        // 连接配置
        config.connection = new ProxyConfig.Connection();
        config.connection.timeout = 5000;
        config.connection.keepalive = true;

        // 性能配置
        config.performance = new ProxyConfig.Performance();
        config.performance.backlog = 128;
        config.performance.maxClientConnections = 100;

        // 禁用管理端点以避免端口冲突
        config.management = new ProxyConfig.Management();
        config.management.enabled = false;

        // 禁用指标
        config.metrics = new ProxyConfig.Metrics();
        config.metrics.enabled = false;

        // 禁用限流
        config.rateLimit = new ProxyConfig.RateLimit();
        config.rateLimit.enabled = false;

        return config;
    }

    /**
     * 创建 HTTP 客户端，子类可以覆盖
     */
    protected CloseableHttpClient createHttpClient() throws Exception {
        SSLContext sslContext = testCerts.createTrustAllSSLContext();

        SSLConnectionSocketFactory sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();

        PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslSocketFactory)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(30))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .build();
    }

    /**
     * 发送 GET 请求到代理
     */
    protected HttpResponse sendGetRequest(String path) throws Exception {
        String url = getProxyUrl() + path;
        HttpGet request = new HttpGet(url);
        request.setHeader("Accept", "text/plain");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return new HttpResponse(
                    response.getCode(),
                    EntityUtils.toString(response.getEntity())
            );
        }
    }

    /**
     * 发送 POST 请求到代理
     */
    protected HttpResponse sendPostRequest(String path, String body) throws Exception {
        String url = getProxyUrl() + path;
        HttpPost request = new HttpPost(url);
        request.setHeader("Content-Type", "text/plain");
        request.setEntity(new StringEntity(body));

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            return new HttpResponse(
                    response.getCode(),
                    EntityUtils.toString(response.getEntity())
            );
        }
    }

    /**
     * 获取代理 URL
     */
    protected String getProxyUrl() {
        // 代理服务器始终使用 SSL/TLS
        return "https://localhost:" + proxyPort;
    }

    /**
     * 查找可用端口
     */
    protected static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Cannot find available port", e);
        }
    }

    /**
     * HTTP 响应封装类
     */
    protected static class HttpResponse {
        public final int statusCode;
        public final String body;

        public HttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public String toString() {
            return "HttpResponse{statusCode=" + statusCode + ", body='" + body + "'}";
        }
    }
}
