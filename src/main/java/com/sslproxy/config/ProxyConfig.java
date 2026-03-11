package com.sslproxy.config;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/**
 * 代理服务器配置类
 * <p>
 * 负责从 YAML 文件加载配置，应用环境变量覆盖，设置默认值并验证配置合法性。
 * 支持从类路径或文件系统加载配置文件。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ProxyConfig {
    
    /**
     * 服务器监听配置
     * 定义代理服务器绑定的地址和端口
     */
    public static class Server {
        /** 监听地址 (例如: 0.0.0.0 表示监听所有网卡) */
        public String host;
        
        /** 监听端口 (1-65535) */
        public int port;
    }

    /**
     * 单个后端服务器配置
     */
    public static class BackendConfig {
        /** 后端主机名或 IP */
        public String host;

        /** 后端端口 */
        public int port;

        /** 后端协议: http 或 https */
        public String protocol;

        /** 权重（用于加权负载均衡），默认为 1 */
        public int weight = 1;
    }

    /**
     * 健康检查配置
     */
    public static class HealthCheckConfig {
        /** 是否启用健康检查 */
        public boolean enabled = false;

        /** 健康检查间隔（秒） */
        public int intervalSeconds = 10;

        /** 健康检查路径（HTTP GET 请求） */
        public String path = "/health";

        /** 健康检查超时时间（毫秒） */
        public int timeoutMs = 5000;

        /** 多少次失败后标记为不健康 */
        public int unhealthyThreshold = 3;

        /** 多少次成功后标记为健康 */
        public int healthyThreshold = 2;
    }

    /**
     * 目标后端服务器配置
     * 定义代理转发的目标服务器信息
     * 支持单后端（向后兼容）和多后端负载均衡模式
     */
    public static class Target {
        /** 目标主机名或 IP (单后端模式，向后兼容) */
        public String host;

        /** 目标端口 (单后端模式，向后兼容) */
        public int port;

        /** 目标协议: http 或 https (单后端模式，向后兼容) */
        public String protocol;

        /** 多后端列表（如果配置，优先于 host/port/protocol） */
        public List<BackendConfig> backends;

        /** 负载均衡策略: ROUND_ROBIN, WEIGHTED_ROUND_ROBIN, LEAST_CONNECTIONS, RANDOM */
        public String strategy = "ROUND_ROBIN";

        /** 健康检查配置 */
        public HealthCheckConfig healthCheck;

        /**
         * 检查是否配置了多后端模式
         * @return true 如果配置了 backends 列表
         */
        public boolean isMultiBackendMode() {
            return backends != null && !backends.isEmpty();
        }
    }

    /**
     * 密钥库/信任库配置
     * 用于配置 SSL/TLS 证书和密钥材料的存储位置
     */
    public static class KeystoreConfig {
        /** 密钥库文件路径 (支持相对路径和绝对路径) - 用于 JKS/PKCS12 格式 */
        public String path;
        
        /** PEM 格式的证书链文件路径 (X.509 证书，如 server.crt) */
        public String certFile;
        
        /** PEM 格式的私钥文件路径 (PKCS#8 私钥，如 server.key) */
        public String keyFile;
        
        /** 密钥库密码 (可通过环境变量覆盖，见 applyEnvOverrides 方法) */
        public String password;
        
        /** 密钥库类型: JKS, PKCS12 等 */
        public String type;
    }

    /**
     * SSL/TLS 安全配置
     * 包含证书、客户端认证模式、协议版本和加密套件等设置
     */
    public static class Ssl {
        /** 服务器密钥库 (包含服务器证书和私钥) */
        public KeystoreConfig keystore;

        /** 信任库 (包含受信任的客户端 CA 证书) */
        public KeystoreConfig truststore;

        /** 客户端证书认证模式: NONE(不需要) / OPTIONAL(可选) / REQUIRED(必需) */
        public String clientAuth;

        /** 启用的 TLS 协议版本列表 (例如: TLSv1.2, TLSv1.3) */
        public List<String> protocols;

        /** 可选的加密套件列表 (限制允许的加密算法) */
        public List<String> ciphers;

        /** 证书指纹白名单（可选，如果非空则只允许白名单中的证书） */
        public List<String> whitelistFingerprints;

        /** 证书指纹黑名单（拒绝黑名单中的证书） */
        public List<String> blacklistFingerprints;

        /** SSL 握手超时时间（毫秒），默认 10000 */
        public long handshakeTimeout = 10000;

        /** 证书吊销检查配置 */
        public RevocationConfig revocation;

        /** 客户端证书 RSA 密钥最低强度（bit），低于此值的证书将被拒绝，默认 2048 */
        public int minimumRsaKeyBits = 2048;

        /**
         * Backend TLS trust strategy: SYSTEM | CUSTOM | BOTH
         * SYSTEM: use system default CA only
         * CUSTOM: use ssl.truststore only
         * BOTH: try CUSTOM first, then SYSTEM
         */
        public String backendTrustStrategy = "SYSTEM";
    }

    /**
     * 证书吊销检查配置
     * 支持 OCSP（在线证书状态协议）和 CRL（证书吊销列表）
     */
    public static class RevocationConfig {
        /** 是否启用证书吊销检查 */
        public boolean enabled = false;

        /** 是否启用 OCSP 检查 */
        public boolean ocspEnabled = true;

        /** 是否启用 CRL 检查 */
        public boolean crlEnabled = true;

        /** OCSP 响应器 URL（可选，不配置则使用证书中的 AIA 扩展） */
        public String ocspResponderUrl;

        /** OCSP 请求超时时间（毫秒），默认 5000 */
        public int ocspTimeout = 5000;

        /** CRL 文件路径（可选，用于本地 CRL 文件） */
        public String crlPath;

        /** CRL 分发点 URL（可选，不配置则使用证书中的 CDP 扩展） */
        public String crlUrl;

        /** 如果吊销检查失败（如网络不可达），是否允许证书（软失败模式） */
        public boolean softFail = false;

        /** 是否缓存 OCSP/CRL 结果，默认开启 */
        public boolean cacheEnabled = true;

        /** 缓存过期时间（秒），默认 3600（1小时） */
        public int cacheTtlSeconds = 3600;
    }

    /**
     * 后端连接配置
     * 控制与目标服务器建立连接时的参数
     */
    public static class Connection {
        /** 连接超时时间 (毫秒) */
        public int timeout;

        /** 是否启用 Keep-Alive 长连接 */
        public boolean keepalive;
    }

    /**
     * 后端连接池配置
     */
    public static class BackendPoolConfig {
        /** 是否启用连接池 */
        public boolean enabled = true;

        /** 每个后端的最大连接数 */
        public int maxConnections = 100;

        /** 获取连接超时时间（毫秒） */
        public int acquireTimeout = 5000;

        /** 连接最大空闲时间（毫秒） */
        public int maxIdleTime = 60000;
    }

    /**
     * 性能配置
     * 控制服务器的性能参数
     */
    public static class Performance {
        /** TCP 连接队列大小 */
        public int backlog = 1024;

        /** Worker 线程数，0 表示使用默认值 */
        public int workerThreads = 0;

        /** 最大请求体大小（字节），默认 10MB */
        public int maxRequestSize = 10 * 1024 * 1024;

        /** 最大客户端连接数限制，0 表示不限制，默认 10000 */
        public int maxClientConnections = 10000;

        /** 后端连接池配置 */
        public BackendPoolConfig backendPool;
    }

    /**
     * 管理端点配置
     * 控制独立的管理服务器（健康检查、指标等）
     */
    public static class Management {
        /** 是否启用管理端点 */
        public boolean enabled = false;

        /** 管理服务器监听地址 */
        public String host = "127.0.0.1";

        /** 管理服务器监听端口 */
        public int port = 9090;

        /**
         * 是否启用管理鉴权（建议开启，尤其是暴露到非本机时）
         * <p>
         * 鉴权策略由 ManagementAuthHandler 实现，默认仅保护 /admin/* 端点。
         * </p>
         */
        public boolean authEnabled = true;

        /**
         * 管理鉴权 Token（建议通过环境变量 MANAGEMENT_TOKEN 注入，不要写入配置文件）
         */
        public String authToken;
    }

    /**
     * 指标收集配置
     * 控制 Prometheus 指标的收集和暴露
     */
    public static class Metrics {
        /** 是否启用指标收集 */
        public boolean enabled = true;

        /** 是否包含 JVM 指标 */
        public boolean includeJvmMetrics = true;

        /** 直方图桶边界（毫秒） */
        public List<Double> histogramBuckets;
    }

    /**
     * 限流配置
     * 基于令牌桶算法的请求限流
     */
    public static class RateLimit {
        /** 是否启用限流 */
        public boolean enabled = false;

        /** 全局每秒请求数限制 */
        public int globalRps = 5000;

        /** 全局突发容量 */
        public int globalBurstCapacity = 500;

        /** 每客户端每秒请求数限制 */
        public int clientRps = 200;

        /** 每客户端突发容量 */
        public int clientBurstCapacity = 50;
    }

    /**
     * 熔断器配置
     * 基于失败率的熔断器，防止后端故障导致雪崩
     */
    public static class CircuitBreaker {
        /** 是否启用熔断器 */
        public boolean enabled = false;

        /** 触发熔断的失败率阈值（百分比，0-100），默认 50% */
        public int failureRateThreshold = 50;

        /** 滑动窗口大小（请求数），用于计算失败率 */
        public int slidingWindowSize = 100;

        /** 最小请求数量，低于此值不触发熔断 */
        public int minimumNumberOfCalls = 10;

        /** 熔断器打开后等待的时间（秒），之后进入半开状态 */
        public int waitDurationInOpenState = 60;

        /** 半开状态允许的测试请求数 */
        public int permittedNumberOfCallsInHalfOpenState = 10;

        /** 慢调用阈值（毫秒），超过此时间视为慢调用 */
        public long slowCallDurationThreshold = 5000;

        /** 慢调用率阈值（百分比），超过此值触发熔断 */
        public int slowCallRateThreshold = 100;
    }

    /**
     * 优雅关闭配置
     */
    public static class Shutdown {
        /** 优雅关闭超时时间（秒） */
        public int timeoutSeconds = 60;

        /** 是否等待现有请求完成 */
        public boolean awaitRequests = true;
    }

    /**
     * 访问日志配置
     */
    public static class AccessLog {
        /** 是否启用访问日志 */
        public boolean enabled = false;

        /** 日志格式: JSON 或 COMBINED */
        public String format = "JSON";

        /** 日志文件路径，null 表示输出到控制台 */
        public String path;

        /** 是否异步写入 */
        public boolean async = true;

        /** 异步写入缓冲区大小 */
        public int bufferSize = 8192;

        /** 是否记录请求头 */
        public boolean logHeaders = false;

        /** 是否记录响应体大小 */
        public boolean logBodySize = true;
    }

    /** 服务器监听配置 */
    public Server server;
    
    /** 目标后端服务器配置 */
    public Target target;
    
    /** SSL/TLS 安全配置 */
    public Ssl ssl;

    /** 后端连接配置 */
    public Connection connection;

    /** 性能配置 */
    public Performance performance;

    /** 管理端点配置 */
    public Management management;

    /** 指标收集配置 */
    public Metrics metrics;

    /** 限流配置 */
    public RateLimit rateLimit;

    /** 熔断器配置 */
    public CircuitBreaker circuitBreaker;

    /** 优雅关闭配置 */
    public Shutdown shutdown;

    /** 访问日志配置 */
    public AccessLog accessLog;

    /**
     * 安全运行时配置
     * 控制动态证书名单持久化等安全运维能力
     */
    public static class Security {
        /** 动态名单持久化文件路径。留空则默认为配置文件同目录下的 dynamic-lists.json */
        public String dynamicListPersistencePath = "";
    }

    /** 安全运行时配置 */
    public Security security;

    /**
     * 获取环境变量值，如果不存在则返回默认值
     * 
     * @param key 环境变量名称
     * @param defaultValue 默认值
     * @return 环境变量值或默认值
     */
    public static String getEnvOrDefault(String key, String defaultValue) {
        String v = System.getenv(key);
        return v != null && !v.isEmpty() ? v : defaultValue;
    }

    /**
     * 应用环境变量覆盖
     * <p>
     * 主要用于覆盖敏感信息（如密码），避免在配置文件中明文存储。
     * 支持的环境变量：
     * <ul>
     *   <li>KEYSTORE_PASSWORD - 覆盖密钥库密码</li>
     *   <li>TRUSTSTORE_PASSWORD - 覆盖信任库密码</li>
     * </ul>
     * </p>
     */
    public void applyEnvOverrides() {
        if (ssl != null && ssl.keystore != null) {
            ssl.keystore.password = getEnvOrDefault("KEYSTORE_PASSWORD", ssl.keystore.password);
        }
        if (ssl != null && ssl.truststore != null) {
            ssl.truststore.password = getEnvOrDefault("TRUSTSTORE_PASSWORD", ssl.truststore.password);
        }
        if (management != null) {
            management.authToken = getEnvOrDefault("MANAGEMENT_TOKEN", management.authToken);
        }
    }

    /**
     * 应用默认值
     * <p>
     * 为可选字段填充合理的默认值，减少 YAML 配置文件中必须指定的字段数量。
     * 默认值包括：
     * <ul>
     *   <li>server.host: 0.0.0.0 (监听所有网络接口)</li>
     *   <li>target.protocol: https</li>
     *   <li>ssl.keystore.type: PKCS12</li>
     *   <li>ssl.truststore.type: JKS</li>
     *   <li>ssl.protocols: [TLSv1.3, TLSv1.2]</li>
     * </ul>
     * </p>
     */
    private void applyDefaults() {
        if (server != null) {
            if (server.host == null || server.host.isEmpty()) {
                server.host = "0.0.0.0";
            }
        }
        if (target != null) {
            if (target.protocol == null || target.protocol.isEmpty()) {
                target.protocol = "https";
            }
        }
        if (ssl != null) {
            if (ssl.keystore != null && (ssl.keystore.type == null || ssl.keystore.type.isEmpty())) {
                // PKCS12 是 .p12 文件的推荐类型
                ssl.keystore.type = "PKCS12";
            }
            if (ssl.truststore != null && (ssl.truststore.type == null || ssl.truststore.type.isEmpty())) {
                // JKS 是常用的信任库类型
                ssl.truststore.type = "JKS";
            }
            if (ssl.backendTrustStrategy == null || ssl.backendTrustStrategy.isEmpty()) {
                ssl.backendTrustStrategy = "SYSTEM";
            }
            if (ssl.protocols == null || ssl.protocols.isEmpty()) {
                // 默认启用 TLS 1.3 和 1.2，优先使用 1.3
                ssl.protocols = Arrays.asList("TLSv1.3", "TLSv1.2");
            }
        }
        // 性能配置默认值
        if (performance == null) {
            performance = new Performance();
        }
        if (performance.backlog <= 0) {
            performance.backlog = 1024;
        }
        if (performance.backendPool == null) {
            performance.backendPool = new BackendPoolConfig();
        }
        // 管理端点配置默认值
        if (management == null) {
            management = new Management();
        }
        // 指标配置默认值
        if (metrics == null) {
            metrics = new Metrics();
        }
        if (metrics.histogramBuckets == null || metrics.histogramBuckets.isEmpty()) {
            metrics.histogramBuckets = Arrays.asList(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0);
        }
        // 限流配置默认值
        if (rateLimit == null) {
            rateLimit = new RateLimit();
        }
        // 优雅关闭配置默认值
        if (shutdown == null) {
            shutdown = new Shutdown();
        }
        // 安全运行时配置默认值
        if (security == null) {
            security = new Security();
        }
    }

    /**
     * 验证配置的完整性和正确性
     * <p>
     * 检查项包括：
     * <ul>
     *   <li>必需字段是否存在</li>
     *   <li>端口号是否在有效范围 (1-65535)</li>
     *   <li>协议类型是否合法 (http/https)</li>
     *   <li>客户端认证模式是否有效 (NONE/OPTIONAL/REQUIRED)</li>
     *   <li>证书路径是否已配置</li>
     * </ul>
     * </p>
     * 
     * @throws IllegalArgumentException 当配置无效时
     * @throws NullPointerException 当必需的配置节缺失时
     */
    public void validate() {
        Objects.requireNonNull(server, "server config is required");
        if (server.port < 1 || server.port > 65535) {
            throw new IllegalArgumentException("server.port out of range");
        }
        if (server.host == null || server.host.isEmpty()) {
            throw new IllegalArgumentException("server.host is required");
        }
        Objects.requireNonNull(target, "target config is required");
        if (target.port < 1 || target.port > 65535) {
            throw new IllegalArgumentException("target.port out of range");
        }
        if (target.host == null || target.host.isEmpty()) {
            throw new IllegalArgumentException("target.host is required");
        }
        if (target.protocol == null || target.protocol.isEmpty()) {
            throw new IllegalArgumentException("target.protocol is required");
        }
        String proto = target.protocol.toLowerCase();
        if (!proto.equals("http") && !proto.equals("https")) {
            throw new IllegalArgumentException("target.protocol must be 'http' or 'https'");
        }
        Objects.requireNonNull(ssl, "ssl config is required");
        Objects.requireNonNull(ssl.keystore, "ssl.keystore is required");
        Objects.requireNonNull(ssl.truststore, "ssl.truststore is required");
        if (ssl.clientAuth == null || ssl.clientAuth.isEmpty()) {
            ssl.clientAuth = "REQUIRED"; // default
        } else {
            String mode = ssl.clientAuth.toUpperCase();
            if (!mode.equals("NONE") && !mode.equals("OPTIONAL") && !mode.equals("REQUIRED")) {
                throw new IllegalArgumentException("ssl.clientAuth must be one of NONE/OPTIONAL/REQUIRED");
            }
            ssl.clientAuth = mode;
        }
        // 验证密钥库配置：必须配置 path (JKS/PKCS12) 或 certFile+keyFile (PEM)
        boolean hasKeystorePath = ssl.keystore.path != null && !ssl.keystore.path.isEmpty();
        boolean hasPemFiles = (ssl.keystore.certFile != null && !ssl.keystore.certFile.isEmpty()) 
                           && (ssl.keystore.keyFile != null && !ssl.keystore.keyFile.isEmpty());
        
        if (!hasKeystorePath && !hasPemFiles) {
            throw new IllegalArgumentException(
                "ssl.keystore: 必须配置 'path' (JKS/PKCS12格式) 或 'certFile'+'keyFile' (PEM格式)"
            );
        }
        if (ssl.truststore.path == null || ssl.truststore.path.isEmpty()) {
            throw new IllegalArgumentException("ssl.truststore.path is required");
        }

        // 验证证书文件存在性（仅对文件系统路径进行检查，跳过 classpath 资源）
        validateCertificateFilesExist();

        // 验证连接配置
        if (connection != null && connection.timeout < 0) {
            throw new IllegalArgumentException("connection.timeout must be >= 0");
        }

        // 验证性能配置
        if (performance != null) {
            if (performance.backlog <= 0) {
                throw new IllegalArgumentException("performance.backlog must be > 0");
            }
            if (performance.workerThreads < 0) {
                throw new IllegalArgumentException("performance.workerThreads must be >= 0");
            }
            if (performance.maxRequestSize <= 0) {
                throw new IllegalArgumentException("performance.maxRequestSize must be > 0");
            }
        }

        // 验证限流配置
        if (rateLimit != null && rateLimit.enabled) {
            if (rateLimit.globalRps <= 0) {
                throw new IllegalArgumentException("rateLimit.globalRps must be > 0 when enabled");
            }
            if (rateLimit.globalBurstCapacity <= 0) {
                throw new IllegalArgumentException("rateLimit.globalBurstCapacity must be > 0 when enabled");
            }
            if (rateLimit.clientRps <= 0) {
                throw new IllegalArgumentException("rateLimit.clientRps must be > 0 when enabled");
            }
            if (rateLimit.clientBurstCapacity <= 0) {
                throw new IllegalArgumentException("rateLimit.clientBurstCapacity must be > 0 when enabled");
            }
        }

        // 验证 SSL 握手超时
        if (ssl.handshakeTimeout < 0) {
            throw new IllegalArgumentException("ssl.handshakeTimeout must be >= 0");
        }
        if (ssl.backendTrustStrategy != null) {
            String strategy = ssl.backendTrustStrategy.toUpperCase();
            if (!strategy.equals("SYSTEM") && !strategy.equals("CUSTOM") && !strategy.equals("BOTH")) {
                throw new IllegalArgumentException("ssl.backendTrustStrategy must be one of SYSTEM/CUSTOM/BOTH");
            }
            ssl.backendTrustStrategy = strategy;
        }

        // 验证管理端口安全配置
        if (management != null && management.enabled) {
            boolean exposedExternally = !management.host.equals("127.0.0.1")
                                      && !management.host.equals("localhost")
                                      && !management.host.equals("::1");

            if (exposedExternally) {
                if (!management.authEnabled) {
                    throw new IllegalArgumentException(
                        "Management endpoint exposed externally (host=" + management.host +
                        ") but authEnabled=false. This is a security risk. " +
                        "Either set management.host=127.0.0.1 or enable authentication.");
                }
                if (management.authToken == null || management.authToken.isBlank()) {
                    throw new IllegalArgumentException(
                        "Management endpoint exposed externally but authToken is not configured. " +
                        "Set MANAGEMENT_TOKEN environment variable or configure management.authToken.");
                }
            }
        }
    }

    /**
     * 验证证书文件是否存在
     * <p>
     * 仅对文件系统路径进行检查，跳过 classpath 资源（以 classpath: 开头或不包含路径分隔符的文件名）。
     * 这样可以在启动时提前发现配置错误，而不是延迟到运行时。
     * </p>
     */
    private void validateCertificateFilesExist() {
        // 检查密钥库路径
        if (ssl.keystore.path != null && !ssl.keystore.path.isEmpty()) {
            validateFileExists(ssl.keystore.path, "ssl.keystore.path");
        }
        if (ssl.keystore.certFile != null && !ssl.keystore.certFile.isEmpty()) {
            validateFileExists(ssl.keystore.certFile, "ssl.keystore.certFile");
        }
        if (ssl.keystore.keyFile != null && !ssl.keystore.keyFile.isEmpty()) {
            validateFileExists(ssl.keystore.keyFile, "ssl.keystore.keyFile");
        }

        // 检查信任库路径
        if (ssl.truststore.path != null && !ssl.truststore.path.isEmpty()) {
            validateFileExists(ssl.truststore.path, "ssl.truststore.path");
        }
    }

    /**
     * 验证单个文件是否存在
     *
     * @param path      文件路径
     * @param fieldName 配置字段名（用于错误消息）
     */
    private void validateFileExists(String path, String fieldName) {
        // 跳过 classpath 资源
        if (path.startsWith("classpath:")) {
            return;
        }

        // 检查是否是相对路径（可能是 classpath 资源）
        Path filePath = Path.of(path);

        // 如果是绝对路径，直接检查
        if (filePath.isAbsolute()) {
            if (!Files.exists(filePath)) {
                throw new IllegalArgumentException(
                        fieldName + " 文件不存在: " + path);
            }
            return;
        }

        // 相对路径：先尝试文件系统，如果不存在则假设是 classpath 资源（稍后加载时验证）
        if (Files.exists(filePath)) {
            return; // 文件存在
        }

        // 检查 classpath 资源
        if (ProxyConfig.class.getClassLoader().getResource(path) == null) {
            throw new IllegalArgumentException(
                    fieldName + " 文件不存在（文件系统和 classpath 均未找到）: " + path);
        }
    }

    /**
     * 从类路径加载配置文件
     * <p>
     * 加载后会自动执行以下操作：
     * <ol>
     *   <li>应用默认值 ({@link #applyDefaults()})</li>
     *   <li>应用环境变量覆盖 ({@link #applyEnvOverrides()})</li>
     *   <li>验证配置 ({@link #validate()})</li>
     * </ol>
     * </p>
     * 
     * @param resourcePath 类路径中的资源路径 (例如: "application.yml")
     * @return 已加载并验证的配置对象
     * @throws IllegalStateException 如果资源未找到或加载失败
     * @throws RuntimeException 如果发生 IO 或解析错误
     */
    public static ProxyConfig loadFromClasspath(String resourcePath) {
        Yaml yaml = new Yaml(new Constructor(ProxyConfig.class, new LoaderOptions()));
        try (InputStream in = ProxyConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Cannot find resource: " + resourcePath);
            }
            ProxyConfig cfg = yaml.load(in);
            if (cfg == null) {
                throw new IllegalStateException("Failed to load configuration from: " + resourcePath);
            }
            cfg.applyDefaults();
            cfg.applyEnvOverrides();
            cfg.validate();
            return cfg;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration", e);
        }
    }

    /**
     * 从文件系统路径加载配置文件
     * <p>
     * 加载后会自动执行以下操作：
     * <ol>
     *   <li>应用默认值 ({@link #applyDefaults()})</li>
     *   <li>应用环境变量覆盖 ({@link #applyEnvOverrides()})</li>
     *   <li>验证配置 ({@link #validate()})</li>
     * </ol>
     * </p>
     * 
     * @param filePath 文件系统中的配置文件路径 (绝对或相对路径)
     * @return 已加载并验证的配置对象
     * @throws IllegalStateException 如果文件加载失败
     * @throws RuntimeException 如果发生 IO 或解析错误
     */
    public static ProxyConfig loadFromFile(String filePath) {
        Yaml yaml = new Yaml(new Constructor(ProxyConfig.class, new LoaderOptions()));
        try (FileInputStream fis = new FileInputStream(filePath)) {
            ProxyConfig cfg = yaml.load(fis);
            if (cfg == null) {
                throw new IllegalStateException("Failed to load configuration from file: " + filePath);
            }
            cfg.applyDefaults();
            cfg.applyEnvOverrides();
            cfg.validate();
            return cfg;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration from file", e);
        }
    }

    /**
     * 智能加载配置文件 (自动判断文件系统或类路径)
     * <p>
     * 加载策略：
     * <ul>
     *   <li>如果参数为 null 或空字符串，从类路径加载默认的 "application.yml"</li>
     *   <li>如果参数指向的文件系统路径存在，从文件系统加载</li>
     *   <li>否则，尝试从类路径加载</li>
     * </ul>
     * 这是最灵活的加载方法，推荐在应用启动时使用。
     * </p>
     * 
     * @param pathOrResource 文件路径或类路径资源名称，null 或空字符串表示使用默认配置
     * @return 已加载并验证的配置对象
     * @throws RuntimeException 如果加载或验证失败
     */
    public static ProxyConfig load(String pathOrResource) {
        if (pathOrResource == null || pathOrResource.isEmpty()) {
            return loadFromClasspath("application.yml");
        }
        try {
            if (Files.exists(Path.of(pathOrResource))) {
                return loadFromFile(pathOrResource);
            }
        } catch (Exception ignored) {
            // 如果 Path.of 失败 (例如特殊字符)，回退到类路径加载
        }
        return loadFromClasspath(pathOrResource);
    }

    /**
     * 计算配置哈希值（不含敏感字段）
     * <p>
     * 基于关键配置字段生成 SHA-256 摘要的前 8 位十六进制字符串，
     * 用于多实例配置一致性检测（配置漂移检测）。
     * 敏感字段（密码、authToken）不参与哈希计算。
     * </p>
     *
     * @return 8 字符的十六进制哈希摘要
     */
    public String computeConfigHash() {
        StringBuilder sb = new StringBuilder();
        if (server != null) {
            sb.append("server.host=").append(server.host).append('\n');
            sb.append("server.port=").append(server.port).append('\n');
        }
        if (target != null) {
            sb.append("target.host=").append(target.host).append('\n');
            sb.append("target.port=").append(target.port).append('\n');
            sb.append("target.protocol=").append(target.protocol).append('\n');
            sb.append("target.strategy=").append(target.strategy).append('\n');
        }
        if (ssl != null) {
            sb.append("ssl.clientAuth=").append(ssl.clientAuth).append('\n');
            sb.append("ssl.protocols=").append(ssl.protocols).append('\n');
            sb.append("ssl.handshakeTimeout=").append(ssl.handshakeTimeout).append('\n');
            sb.append("ssl.backendTrustStrategy=").append(ssl.backendTrustStrategy).append('\n');
            sb.append("ssl.minimumRsaKeyBits=").append(ssl.minimumRsaKeyBits).append('\n');
        }
        if (performance != null) {
            sb.append("performance.maxClientConnections=").append(performance.maxClientConnections).append('\n');
            sb.append("performance.maxRequestSize=").append(performance.maxRequestSize).append('\n');
        }
        if (rateLimit != null) {
            sb.append("rateLimit.enabled=").append(rateLimit.enabled).append('\n');
            sb.append("rateLimit.globalRps=").append(rateLimit.globalRps).append('\n');
            sb.append("rateLimit.clientRps=").append(rateLimit.clientRps).append('\n');
        }
        if (management != null) {
            sb.append("management.enabled=").append(management.enabled).append('\n');
            sb.append("management.host=").append(management.host).append('\n');
            sb.append("management.port=").append(management.port).append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "00000000";
        }
    }
}
