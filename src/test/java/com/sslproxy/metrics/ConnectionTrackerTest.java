package com.sslproxy.metrics;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ConnectionTracker 单元测试
 */
class ConnectionTrackerTest {

    private ConnectionTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ConnectionTracker(ProxyMetrics.noop());
    }

    @Test
    void testRegisterAndUnregister() {
        Channel channel = createMockChannel("channel-1");

        tracker.register(channel, "fingerprint-abc");
        assertEquals(1, tracker.getActiveCount());

        tracker.unregister(channel);
        assertEquals(0, tracker.getActiveCount());
    }

    @Test
    void testRegisterWithNullFingerprint() {
        Channel channel = createMockChannel("channel-2");

        tracker.register(channel, null);
        assertEquals(1, tracker.getActiveCount());

        tracker.unregister(channel);
        assertEquals(0, tracker.getActiveCount());
    }

    @Test
    void testMultipleRegistrations() {
        Channel channel1 = createMockChannel("channel-1");
        Channel channel2 = createMockChannel("channel-2");
        Channel channel3 = createMockChannel("channel-3");

        tracker.register(channel1, "fp1");
        tracker.register(channel2, "fp2");
        tracker.register(channel3, "fp3");

        assertEquals(3, tracker.getActiveCount());

        tracker.unregister(channel1);
        assertEquals(2, tracker.getActiveCount());

        tracker.unregister(channel2);
        tracker.unregister(channel3);
        assertEquals(0, tracker.getActiveCount());
    }

    @Test
    void testUnregisterNonExistentChannel() {
        Channel channel = createMockChannel("non-existent");

        // 不应抛出异常
        assertDoesNotThrow(() -> tracker.unregister(channel));
        assertEquals(0, tracker.getActiveCount());
    }

    @Test
    void testDuplicateRegistration() {
        Channel channel = createMockChannel("channel-dup");

        tracker.register(channel, "fp1");
        tracker.register(channel, "fp2"); // 重复注册同一 channel

        // 应该只计数一次
        assertEquals(1, tracker.getActiveCount());
    }

    @Test
    void testGetActiveChannels() {
        Channel channel1 = createMockChannel("ch-1");
        Channel channel2 = createMockChannel("ch-2");

        tracker.register(channel1, "fp-a");
        tracker.register(channel2, "fp-b");

        var channels = tracker.getActiveChannels();
        assertNotNull(channels);
        assertEquals(2, channels.size());
    }

    @Test
    void testGetCountByFingerprint() {
        Channel channel1 = createMockChannel("ch-1");
        Channel channel2 = createMockChannel("ch-2");
        Channel channel3 = createMockChannel("ch-3");

        String commonFingerprint = "COMMON:FP:123";
        tracker.register(channel1, commonFingerprint);
        tracker.register(channel2, commonFingerprint);
        tracker.register(channel3, "OTHER:FP:456");

        int count = tracker.getCountByFingerprint(commonFingerprint);
        assertEquals(2, count);
    }

    @Test
    void testGetCountByFingerprintNotFound() {
        int result = tracker.getCountByFingerprint("NON:EXISTENT:FP");
        assertEquals(0, result);
    }

    private Channel createMockChannel(String channelIdStr) {
        Channel channel = mock(Channel.class);
        ChannelId channelId = mock(ChannelId.class);
        when(channelId.asLongText()).thenReturn(channelIdStr);
        when(channelId.asShortText()).thenReturn(channelIdStr);
        when(channel.id()).thenReturn(channelId);
        return channel;
    }
}
