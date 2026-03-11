package com.sslproxy.audit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 审计日志记录器
 * <p>
 * 提供结构化的审计日志记录功能：
 * <ul>
 *   <li>使用 MDC 关联请求追踪 ID</li>
 *   <li>输出 JSON 格式日志便于 ELK 分析</li>
 *   <li>敏感信息脱敏处理</li>
 * </ul>
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class AuditLogger {
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    // MDC 上下文键
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_CLIENT_IP = "clientIp";
    public static final String MDC_CLIENT_CERT_CN = "clientCertCN";

    public AuditLogger() {
    }

    /**
     * 记录审计事件
     *
     * @param eventType 事件类型
     * @param details   事件详情
     */
    public void log(AuditEventType eventType, Map<String, Object> details) {
        String jsonLog = buildJsonLog(eventType, details, null);
        writeLog(eventType, jsonLog);
    }

    /**
     * 记录审计事件（带异常）
     *
     * @param eventType 事件类型
     * @param details   事件详情
     * @param throwable 异常信息
     */
    public void log(AuditEventType eventType, Map<String, Object> details, Throwable throwable) {
        String jsonLog = buildJsonLog(eventType, details, throwable);
        writeLog(eventType, jsonLog);
    }

    /**
     * 记录简单审计事件
     *
     * @param eventType 事件类型
     * @param message   消息
     */
    public void log(AuditEventType eventType, String message) {
        Map<String, Object> details = new HashMap<>();
        details.put("message", message);
        log(eventType, details);
    }

    /**
     * 构建 JSON 格式日志
     */
    private String buildJsonLog(AuditEventType eventType, Map<String, Object> details, Throwable throwable) {
        StringBuilder json = new StringBuilder();
        json.append("{");

        // 基础字段
        json.append("\"@timestamp\":\"").append(Instant.now().toString()).append("\"");
        json.append(",\"event_type\":\"").append(eventType.getCode()).append("\"");
        json.append(",\"event_category\":\"").append(eventType.getCategory()).append("\"");
        json.append(",\"event_description\":\"").append(escapeJson(eventType.getDescription())).append("\"");

        // MDC 上下文
        String requestId = MDC.get(MDC_REQUEST_ID);
        if (requestId != null) {
            json.append(",\"request_id\":\"").append(escapeJson(requestId)).append("\"");
        }

        String clientIp = MDC.get(MDC_CLIENT_IP);
        if (clientIp != null) {
            json.append(",\"client_ip\":\"").append(escapeJson(maskIp(clientIp))).append("\"");
        }

        String clientCertCN = MDC.get(MDC_CLIENT_CERT_CN);
        if (clientCertCN != null) {
            json.append(",\"client_cert_cn\":\"").append(escapeJson(clientCertCN)).append("\"");
        }

        // 详情字段
        if (details != null && !details.isEmpty()) {
            json.append(",\"details\":{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : details.entrySet()) {
                if (!first) {
                    json.append(",");
                }
                first = false;
                json.append("\"").append(escapeJson(entry.getKey())).append("\":");
                appendValue(json, entry.getValue());
            }
            json.append("}");
        }

        // 异常信息
        if (throwable != null) {
            json.append(",\"error\":{");
            json.append("\"type\":\"").append(throwable.getClass().getName()).append("\"");
            json.append(",\"message\":\"").append(escapeJson(throwable.getMessage())).append("\"");
            json.append("}");
        }

        json.append("}");
        return json.toString();
    }

    /**
     * 添加值到 JSON
     */
    private void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Number) {
            json.append(value);
        } else if (value instanceof Boolean) {
            json.append(value);
        } else {
            // 字符串或其他类型，需要转义
            String strValue = sanitize(value.toString());
            json.append("\"").append(escapeJson(strValue)).append("\"");
        }
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 敏感信息脱敏：IP 地址
     * 保留前两段，后两段用 * 代替
     */
    private String maskIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return ip;
        }

        // 移除可能的端口和斜杠
        String cleanIp = ip.replaceAll("^/", "").split(":")[0];

        // IPv4
        if (cleanIp.contains(".")) {
            String[] parts = cleanIp.split("\\.");
            if (parts.length == 4) {
                return parts[0] + "." + parts[1] + ".*.*";
            }
        }

        // IPv6 或其他格式，返回部分
        if (cleanIp.length() > 8) {
            return cleanIp.substring(0, 8) + "...";
        }

        return cleanIp;
    }

    /**
     * 通用敏感信息脱敏
     */
    private String sanitize(String value) {
        if (value == null) {
            return null;
        }

        // 脱敏密码字段
        if (value.toLowerCase().contains("password")) {
            return "***REDACTED***";
        }

        // 脱敏私钥
        if (value.contains("PRIVATE KEY")) {
            return "***PRIVATE_KEY_REDACTED***";
        }

        return value;
    }

    /**
     * 写入日志
     */
    private void writeLog(AuditEventType eventType, String jsonLog) {
        switch (eventType.getCategory()) {
            case "security":
            case "warning":
                auditLogger.warn(jsonLog);
                break;
            case "error":
                auditLogger.error(jsonLog);
                break;
            default:
                auditLogger.info(jsonLog);
        }
    }

    // ===== 便捷方法 =====

    /**
     * 设置 MDC 上下文
     */
    public static void setContext(String requestId, String clientIp) {
        if (requestId != null) {
            MDC.put(MDC_REQUEST_ID, requestId);
        }
        if (clientIp != null) {
            MDC.put(MDC_CLIENT_IP, clientIp);
        }
    }

    /**
     * 设置客户端证书 CN
     */
    public static void setClientCertCN(String cn) {
        if (cn != null) {
            MDC.put(MDC_CLIENT_CERT_CN, cn);
        }
    }

    /**
     * 清除 MDC 上下文
     */
    public static void clearContext() {
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_CLIENT_IP);
        MDC.remove(MDC_CLIENT_CERT_CN);
    }

    // ===== 快捷审计方法 =====

    /**
     * 记录客户端证书验证成功
     */
    public void logCertValidated(String cn, String fingerprint) {
        Map<String, Object> details = new HashMap<>();
        details.put("cert_cn", cn);
        details.put("fingerprint", fingerprint != null ? fingerprint.substring(0, Math.min(16, fingerprint.length())) + "..." : null);
        log(AuditEventType.CLIENT_CERT_VALIDATED, details);
    }

    /**
     * 记录客户端证书被拒绝
     */
    public void logCertRejected(String cn, String reason) {
        Map<String, Object> details = new HashMap<>();
        details.put("cert_cn", cn);
        details.put("reason", reason);
        log(AuditEventType.CLIENT_CERT_REJECTED, details);
    }

    /**
     * 记录限流触发
     */
    public void logRateLimited(boolean isGlobal, String clientId) {
        Map<String, Object> details = new HashMap<>();
        details.put("client_id", clientId);
        log(isGlobal ? AuditEventType.RATE_LIMIT_GLOBAL_TRIGGERED : AuditEventType.RATE_LIMIT_CLIENT_TRIGGERED, details);
    }

    /**
     * 记录熔断器状态变更
     */
    public void logCircuitBreakerStateChange(String fromState, String toState, double failureRate) {
        Map<String, Object> details = new HashMap<>();
        details.put("from_state", fromState);
        details.put("to_state", toState);
        details.put("failure_rate", failureRate);

        AuditEventType eventType = switch (toState) {
            case "OPEN" -> AuditEventType.CIRCUIT_BREAKER_OPENED;
            case "CLOSED" -> AuditEventType.CIRCUIT_BREAKER_CLOSED;
            case "HALF_OPEN" -> AuditEventType.CIRCUIT_BREAKER_HALF_OPEN;
            default -> AuditEventType.CIRCUIT_BREAKER_OPENED;
        };

        log(eventType, details);
    }

    /**
     * 记录配置重载
     */
    public void logConfigReloaded(String configType) {
        Map<String, Object> details = new HashMap<>();
        details.put("config_type", configType);
        log(AuditEventType.CONFIG_RELOADED, details);
    }

    /**
     * 记录 SSL 证书重载
     */
    public void logSslCertReloaded(long reloadCount) {
        Map<String, Object> details = new HashMap<>();
        details.put("reload_count", reloadCount);
        log(AuditEventType.SSL_CERT_RELOADED, details);
    }

    /**
     * 记录证书即将过期
     */
    public void logCertificateExpiring(String certType, long daysUntilExpiry) {
        Map<String, Object> details = new HashMap<>();
        details.put("cert_type", certType);
        details.put("days_until_expiry", daysUntilExpiry);
        log(AuditEventType.CERTIFICATE_EXPIRING, details);
    }
}
