package com.sslproxy.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

/**
 * MaxConnectionsHandler 单元测试
 */
class MaxConnectionsHandlerTest {

    @Mock
    private ChannelHandlerContext ctx;

    @Mock
    private Channel channel;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(ctx.channel()).thenReturn(channel);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
    }

    // ========== 构造函数测试 ==========

    @Test
    void testConstructor_PositiveMax() {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(100);
        assertEquals(100, handler.getMaxConnections());
        assertEquals(0, handler.getCurrentConnections());
    }

    @Test
    void testConstructor_ZeroMax_Unlimited() {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(0);
        assertEquals(0, handler.getMaxConnections());
    }

    @Test
    void testConstructor_NegativeMax_Unlimited() {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(-1);
        assertEquals(-1, handler.getMaxConnections());
    }

    // ========== channelActive 测试 ==========

    @Test
    void testChannelActive_BelowLimit_Accepted() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(10);

        handler.channelActive(ctx);

        assertEquals(1, handler.getCurrentConnections());
        verify(ctx, never()).close();
    }

    @Test
    void testChannelActive_AtLimit_Accepted() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(2);

        // 第一个连接
        handler.channelActive(ctx);
        assertEquals(1, handler.getCurrentConnections());

        // 第二个连接（达到限制）
        handler.channelActive(ctx);
        assertEquals(2, handler.getCurrentConnections());

        verify(ctx, never()).close();
    }

    @Test
    void testChannelActive_ExceedsLimit_Rejected() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(2);

        // 填满限制
        handler.channelActive(ctx);
        handler.channelActive(ctx);
        assertEquals(2, handler.getCurrentConnections());

        // 第三个连接应该被拒绝
        handler.channelActive(ctx);
        assertEquals(2, handler.getCurrentConnections()); // 仍然是 2
        verify(ctx, times(1)).close(); // 被关闭一次
    }

    @Test
    void testChannelActive_UnlimitedMax_AlwaysAccepted() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(0);

        // 大量连接都应该被接受
        for (int i = 0; i < 100; i++) {
            handler.channelActive(ctx);
        }

        // 当 maxConnections <= 0 时，不跟踪连接数
        assertEquals(0, handler.getCurrentConnections());
        verify(ctx, never()).close();
    }

    // ========== channelInactive 测试 ==========

    @Test
    void testChannelInactive_DecrementsCounter() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(10);

        handler.channelActive(ctx);
        handler.channelActive(ctx);
        assertEquals(2, handler.getCurrentConnections());

        handler.channelInactive(ctx);
        assertEquals(1, handler.getCurrentConnections());

        handler.channelInactive(ctx);
        assertEquals(0, handler.getCurrentConnections());
    }

    @Test
    void testChannelInactive_NeverGoesNegative() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(10);

        // 没有 active 就 inactive
        handler.channelInactive(ctx);
        assertEquals(0, handler.getCurrentConnections());

        handler.channelInactive(ctx);
        assertEquals(0, handler.getCurrentConnections());
    }

    @Test
    void testChannelInactive_UnlimitedMax_NoTracking() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(0);

        handler.channelActive(ctx);
        handler.channelInactive(ctx);

        assertEquals(0, handler.getCurrentConnections());
    }

    // ========== 并发场景测试 ==========

    @Test
    void testConcurrentConnections() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(100);

        // 模拟多个连接
        for (int i = 0; i < 50; i++) {
            handler.channelActive(ctx);
        }
        assertEquals(50, handler.getCurrentConnections());

        // 模拟关闭一些连接
        for (int i = 0; i < 20; i++) {
            handler.channelInactive(ctx);
        }
        assertEquals(30, handler.getCurrentConnections());

        // 再添加一些
        for (int i = 0; i < 10; i++) {
            handler.channelActive(ctx);
        }
        assertEquals(40, handler.getCurrentConnections());
    }

    // ========== 边界条件测试 ==========

    @Test
    void testMaxConnections_ExactlyOne() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(1);

        // 第一个连接
        handler.channelActive(ctx);
        assertEquals(1, handler.getCurrentConnections());

        // 第二个连接被拒绝
        handler.channelActive(ctx);
        assertEquals(1, handler.getCurrentConnections());
        verify(ctx, times(1)).close();

        // 释放后可以再接受
        handler.channelInactive(ctx);
        assertEquals(0, handler.getCurrentConnections());

        handler.channelActive(ctx);
        assertEquals(1, handler.getCurrentConnections());
    }

    @Test
    void testSharable_SameInstanceMultipleChannels() throws Exception {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(5);

        // 同一个 handler 处理多个 channel
        ChannelHandlerContext ctx2 = mock(ChannelHandlerContext.class);
        Channel channel2 = mock(Channel.class);
        when(ctx2.channel()).thenReturn(channel2);
        when(channel2.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.2", 12346));

        handler.channelActive(ctx);
        handler.channelActive(ctx2);

        assertEquals(2, handler.getCurrentConnections());

        handler.channelInactive(ctx);
        assertEquals(1, handler.getCurrentConnections());

        handler.channelInactive(ctx2);
        assertEquals(0, handler.getCurrentConnections());
    }
}
