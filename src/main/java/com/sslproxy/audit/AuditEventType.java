package com.sslproxy.audit;

/**
 * 审计事件类型枚举
 * <p>
 * 定义所有需要审计记录的安全和操作事件类型
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public enum AuditEventType {

    // ===== 证书相关事件 =====

    /** 客户端证书验证成功 */
    CLIENT_CERT_VALIDATED("CLIENT_CERT_VALIDATED", "security", "Client certificate validated successfully"),

    /** 客户端证书被拒绝 */
    CLIENT_CERT_REJECTED("CLIENT_CERT_REJECTED", "security", "Client certificate rejected"),

    /** 客户端证书已过期 */
    CLIENT_CERT_EXPIRED("CLIENT_CERT_EXPIRED", "security", "Client certificate expired"),

    /** 客户端证书在黑名单中 */
    CLIENT_CERT_BLACKLISTED("CLIENT_CERT_BLACKLISTED", "security", "Client certificate is blacklisted"),

    /** 客户端证书不在白名单中 */
    CLIENT_CERT_NOT_WHITELISTED("CLIENT_CERT_NOT_WHITELISTED", "security", "Client certificate not in whitelist"),

    /** 证书吊销检查失败 */
    CERT_REVOCATION_CHECK_FAILED("CERT_REVOCATION_CHECK_FAILED", "security", "Certificate revocation check failed"),

    /** 证书即将过期警告 */
    CERTIFICATE_EXPIRING("CERTIFICATE_EXPIRING", "warning", "Certificate is expiring soon"),

    // ===== 限流相关事件 =====

    /** 全局限流触发 */
    RATE_LIMIT_GLOBAL_TRIGGERED("RATE_LIMIT_GLOBAL_TRIGGERED", "security", "Global rate limit triggered"),

    /** 客户端限流触发 */
    RATE_LIMIT_CLIENT_TRIGGERED("RATE_LIMIT_CLIENT_TRIGGERED", "security", "Client rate limit triggered"),

    // ===== 熔断器相关事件 =====

    /** 熔断器打开 */
    CIRCUIT_BREAKER_OPENED("CIRCUIT_BREAKER_OPENED", "warning", "Circuit breaker opened"),

    /** 熔断器关闭 */
    CIRCUIT_BREAKER_CLOSED("CIRCUIT_BREAKER_CLOSED", "info", "Circuit breaker closed"),

    /** 熔断器半开 */
    CIRCUIT_BREAKER_HALF_OPEN("CIRCUIT_BREAKER_HALF_OPEN", "info", "Circuit breaker half-open"),

    /** 请求被熔断器拒绝 */
    CIRCUIT_BREAKER_REJECTED("CIRCUIT_BREAKER_REJECTED", "warning", "Request rejected by circuit breaker"),

    // ===== 连接相关事件 =====

    /** 最大连接数超限 */
    MAX_CONNECTIONS_EXCEEDED("MAX_CONNECTIONS_EXCEEDED", "warning", "Maximum connections exceeded"),

    /** 后端连接失败 */
    BACKEND_CONNECTION_FAILED("BACKEND_CONNECTION_FAILED", "error", "Backend connection failed"),

    /** 后端健康检查失败 */
    BACKEND_HEALTH_CHECK_FAILED("BACKEND_HEALTH_CHECK_FAILED", "warning", "Backend health check failed"),

    /** 后端恢复健康 */
    BACKEND_RECOVERED("BACKEND_RECOVERED", "info", "Backend recovered and healthy"),

    // ===== 配置相关事件 =====

    /** 配置热更新 */
    CONFIG_RELOADED("CONFIG_RELOADED", "info", "Configuration reloaded"),

    /** SSL 证书热更新 */
    SSL_CERT_RELOADED("SSL_CERT_RELOADED", "info", "SSL certificate reloaded"),

    // ===== SSL 握手事件 =====

    /** SSL 握手成功 */
    SSL_HANDSHAKE_SUCCESS("SSL_HANDSHAKE_SUCCESS", "info", "SSL handshake successful"),

    /** SSL 握手失败 */
    SSL_HANDSHAKE_FAILED("SSL_HANDSHAKE_FAILED", "security", "SSL handshake failed"),

    // ===== 管理 API 事件 =====

    /** 管理员添加黑名单 */
    ADMIN_BLACKLIST_ADD("ADMIN_BLACKLIST_ADD", "security", "Admin added fingerprint to blacklist"),

    /** 管理员移除黑名单 */
    ADMIN_BLACKLIST_REMOVE("ADMIN_BLACKLIST_REMOVE", "security", "Admin removed fingerprint from blacklist"),

    /** 管理员添加白名单 */
    ADMIN_WHITELIST_ADD("ADMIN_WHITELIST_ADD", "security", "Admin added fingerprint to whitelist"),

    /** 管理员移除白名单 */
    ADMIN_WHITELIST_REMOVE("ADMIN_WHITELIST_REMOVE", "security", "Admin removed fingerprint from whitelist"),

    /** 管理员触发配置重载 */
    ADMIN_RELOAD("ADMIN_RELOAD", "info", "Admin triggered reload"),

    /** 管理员启动 Drain 模式 */
    ADMIN_DRAIN_START("ADMIN_DRAIN_START", "warning", "Admin started drain mode"),

    /** 管理员取消 Drain 模式 */
    ADMIN_DRAIN_CANCEL("ADMIN_DRAIN_CANCEL", "info", "Admin cancelled drain mode"),

    /** 管理员终止连接 */
    ADMIN_CONNECTION_TERMINATE("ADMIN_CONNECTION_TERMINATE", "security", "Admin terminated connections by fingerprint"),

    /** 管理 API 未授权访问 */
    ADMIN_UNAUTHORIZED("ADMIN_UNAUTHORIZED", "security", "Unauthorized admin API access attempt");

    private final String code;
    private final String category;
    private final String description;

    AuditEventType(String code, String category, String description) {
        this.code = code;
        this.category = category;
        this.description = description;
    }

    /**
     * 获取事件代码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取事件类别（security, warning, info, error）
     */
    public String getCategory() {
        return category;
    }

    /**
     * 获取事件描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 是否为安全相关事件
     */
    public boolean isSecurityEvent() {
        return "security".equals(category);
    }
}
