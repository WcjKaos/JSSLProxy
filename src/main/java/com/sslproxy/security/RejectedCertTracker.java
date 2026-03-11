package com.sslproxy.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 被拒绝证书追踪器
 * <p>
 * 维护一个有界的环形缓冲区，记录最近被拒绝的客户端证书信息。
 * 用于 mTLS 调试端点 {@code GET /admin/rejected-certs}。
 * </p>
 */
public class RejectedCertTracker {
    private static final Logger logger = LoggerFactory.getLogger(RejectedCertTracker.class);
    private static final int DEFAULT_MAX_ENTRIES = 100;

    private final int maxEntries;
    private final ConcurrentLinkedDeque<RejectedCertRecord> records = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger(0);

    /**
     * 被拒绝证书记录
     */
    public static class RejectedCertRecord {
        private final long timestamp;
        private final String clientIp;
        private final String subject;
        private final String fingerprint;
        private final String reason;

        public RejectedCertRecord(String clientIp, String subject, String fingerprint, String reason) {
            this.timestamp = System.currentTimeMillis();
            this.clientIp = clientIp;
            this.subject = subject;
            this.fingerprint = fingerprint;
            this.reason = reason;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", timestamp);
            map.put("clientIp", clientIp);
            if (subject != null) {
                map.put("subject", subject);
            }
            if (fingerprint != null) {
                map.put("fingerprint", fingerprint);
            }
            map.put("reason", reason);
            return map;
        }

        public long getTimestamp() { return timestamp; }
        public String getClientIp() { return clientIp; }
        public String getSubject() { return subject; }
        public String getFingerprint() { return fingerprint; }
        public String getReason() { return reason; }
    }

    public RejectedCertTracker(int maxEntries) {
        this.maxEntries = maxEntries;
    }


    /**
     * 记录一次证书拒绝
     *
     * @param clientIp    客户端 IP
     * @param subject     证书 Subject（可为 null）
     * @param fingerprint 证书指纹（可为 null）
     * @param reason      拒绝原因
     */
    public void record(String clientIp, String subject, String fingerprint, String reason) {
        records.addFirst(new RejectedCertRecord(clientIp, subject, fingerprint, reason));
        if (size.incrementAndGet() > maxEntries) {
            if (records.pollLast() != null) {
                size.decrementAndGet();
            }
        }
        logger.debug("Recorded rejected cert: clientIp={}, reason={}", clientIp, reason);
    }

    /**
     * 获取最近的拒绝记录
     *
     * @param limit 最大返回条数
     * @return 拒绝记录列表（最新在前）
     */
    public List<RejectedCertRecord> getRecords(int limit) {
        List<RejectedCertRecord> result = new ArrayList<>();
        int count = 0;
        for (RejectedCertRecord record : records) {
            if (count >= limit) break;
            result.add(record);
            count++;
        }
        return result;
    }

    public List<RejectedCertRecord> getRecords() {
        return getRecords(maxEntries);
    }

    public int getSize() {
        return size.get();
    }

}
