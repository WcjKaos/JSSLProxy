package com.sslproxy.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.Channel;

/**
 * ConnectionTracker.terminateByFingerprint 单元测试
 */
class ConnectionTrackerTerminateTest {

    private ConnectionTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ConnectionTracker(ProxyMetrics.noop());
    }

    @Test
    void should_returnZero_when_fingerprintNotFound() {
        int result = tracker.terminateByFingerprint(
                "FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF");
        assertThat(result).isZero();
    }

    @Test
    void should_returnZero_when_fingerprintNull() {
        int result = tracker.terminateByFingerprint(null);
        assertThat(result).isZero();
    }

    @Test
    void should_returnZero_when_fingerprintEmpty() {
        int result = tracker.terminateByFingerprint("");
        assertThat(result).isZero();
    }

    @Test
    void should_closeChannel_when_activeChannelFound() {
        // Use a unique fingerprint for this test
        String fp = "01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01:01";
        Channel mockChannel = mock(Channel.class);
        when(mockChannel.isActive()).thenReturn(true);
        when(mockChannel.remoteAddress()).thenReturn(null);

        tracker.register(mockChannel, fp);

        int result = tracker.terminateByFingerprint(fp);
        assertThat(result).isEqualTo(1);
        verify(mockChannel).close();
    }

    @Test
    void should_notCloseChannel_when_channelAlreadyInactive() {
        String fp = "02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02:02";
        Channel mockChannel = mock(Channel.class);
        when(mockChannel.isActive()).thenReturn(false);
        when(mockChannel.remoteAddress()).thenReturn(null);

        tracker.register(mockChannel, fp);

        int result = tracker.terminateByFingerprint(fp);
        assertThat(result).isZero();
        verify(mockChannel, never()).close();
    }

    @Test
    void should_normalizeFingerprint_when_mixedCase() {
        String fpLower = "03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03:03";
        String fpUpper = fpLower.toUpperCase();

        Channel mockChannel = mock(Channel.class);
        when(mockChannel.isActive()).thenReturn(true);
        when(mockChannel.remoteAddress()).thenReturn(null);

        // Register with uppercase (as CertificateUtil produces)
        tracker.register(mockChannel, fpUpper);

        // Terminate with lowercase — should still match after normalization
        int result = tracker.terminateByFingerprint(fpLower);
        assertThat(result).isEqualTo(1);
        verify(mockChannel).close();
    }
}
