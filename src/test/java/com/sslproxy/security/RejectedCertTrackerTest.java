package com.sslproxy.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RejectedCertTracker 单元测试
 */
class RejectedCertTrackerTest {

    private RejectedCertTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RejectedCertTracker(100);
    }

    @Test
    void should_beEmpty_when_noRecordsAdded() {
        assertThat(tracker.getSize()).isZero();
        assertThat(tracker.getRecords()).isEmpty();
    }

    @Test
    void should_recordEntry_when_certRejected() {
        tracker.record("192.168.1.1:5000", "CN=test-client", "AA:BB:CC", "expired");

        assertThat(tracker.getSize()).isEqualTo(1);
        List<RejectedCertTracker.RejectedCertRecord> records = tracker.getRecords();
        assertThat(records).hasSize(1);

        RejectedCertTracker.RejectedCertRecord record = records.get(0);
        assertThat(record.getClientIp()).isEqualTo("192.168.1.1:5000");
        assertThat(record.getSubject()).isEqualTo("CN=test-client");
        assertThat(record.getFingerprint()).isEqualTo("AA:BB:CC");
        assertThat(record.getReason()).isEqualTo("expired");
        assertThat(record.getTimestamp()).isPositive();
    }

    @Test
    void should_returnNewestFirst_when_multipleRecords() {
        tracker.record("1.1.1.1", null, null, "first");
        tracker.record("2.2.2.2", null, null, "second");
        tracker.record("3.3.3.3", null, null, "third");

        List<RejectedCertTracker.RejectedCertRecord> records = tracker.getRecords();
        assertThat(records).hasSize(3);
        assertThat(records.get(0).getReason()).isEqualTo("third");
        assertThat(records.get(1).getReason()).isEqualTo("second");
        assertThat(records.get(2).getReason()).isEqualTo("first");
    }

    @Test
    void should_respectLimit_when_getRecordsWithLimit() {
        for (int i = 0; i < 10; i++) {
            tracker.record("ip" + i, null, null, "reason" + i);
        }

        List<RejectedCertTracker.RejectedCertRecord> records = tracker.getRecords(3);
        assertThat(records).hasSize(3);
        // Most recent first
        assertThat(records.get(0).getReason()).isEqualTo("reason9");
    }

    @Test
    void should_evictOldest_when_exceedsMaxCapacity() {
        // Create a tracker with small capacity
        RejectedCertTracker small = new RejectedCertTracker(5);

        for (int i = 0; i < 10; i++) {
            small.record("ip" + i, null, null, "reason" + i);
        }

        assertThat(small.getSize()).isEqualTo(5);
        List<RejectedCertTracker.RejectedCertRecord> records = small.getRecords();
        // Only the 5 newest should remain
        assertThat(records.get(0).getReason()).isEqualTo("reason9");
        assertThat(records.get(4).getReason()).isEqualTo("reason5");
    }

    @Test
    void should_handleNullSubjectAndFingerprint_when_handshakeFailure() {
        tracker.record("10.0.0.1", null, null, "protocol_mismatch: no appropriate protocol");

        RejectedCertTracker.RejectedCertRecord record = tracker.getRecords().get(0);
        assertThat(record.getSubject()).isNull();
        assertThat(record.getFingerprint()).isNull();
        assertThat(record.getReason()).contains("protocol_mismatch");
    }

    @Test
    void should_produceCorrectMap_when_toMapCalled() {
        tracker.record("1.2.3.4", "CN=client", "FF:EE", "blacklisted");

        Map<String, Object> map = tracker.getRecords().get(0).toMap();
        assertThat(map).containsKey("timestamp");
        assertThat(map).containsEntry("clientIp", "1.2.3.4");
        assertThat(map).containsEntry("subject", "CN=client");
        assertThat(map).containsEntry("fingerprint", "FF:EE");
        assertThat(map).containsEntry("reason", "blacklisted");
    }

    @Test
    void should_omitNullFields_when_toMapCalled() {
        tracker.record("1.2.3.4", null, null, "timeout");

        Map<String, Object> map = tracker.getRecords().get(0).toMap();
        assertThat(map).doesNotContainKey("subject");
        assertThat(map).doesNotContainKey("fingerprint");
        assertThat(map).containsKey("clientIp");
        assertThat(map).containsKey("reason");
    }
}
