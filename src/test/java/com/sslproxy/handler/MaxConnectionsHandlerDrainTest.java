package com.sslproxy.handler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * MaxConnectionsHandler Drain 模式单元测试
 *
 * 验证 startDrain / cancelDrain / isDraining 状态切换，
 * 以及 drain 模式下新连接被拒绝、非 drain 模式下连接正常接受。
 */
class MaxConnectionsHandlerDrainTest {

    @AfterEach
    void tearDown() {
        MaxConnectionsHandler.cancelDrain();
    }

    // ========== isDraining 状态测试 ==========

    @Test
    void should_notBeDraining_byDefault() {
        assertFalse(MaxConnectionsHandler.isDraining());
    }

    @Test
    void should_beDraining_afterStartDrain() {
        MaxConnectionsHandler.startDrain(0);

        assertTrue(MaxConnectionsHandler.isDraining());
    }

    @Test
    void should_notBeDraining_afterCancelDrain() {
        MaxConnectionsHandler.startDrain(0);
        assertTrue(MaxConnectionsHandler.isDraining());

        MaxConnectionsHandler.cancelDrain();

        assertFalse(MaxConnectionsHandler.isDraining());
    }

    // ========== 连接接受/拒绝测试 ==========

    @Test
    void should_rejectNewConnection_whenDraining() {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(0);
        MaxConnectionsHandler.startDrain(0);

        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // Drain 模式下 channelActive 会调用 ctx.close()，导致 channel 被关闭
        assertFalse(channel.isOpen(), "Channel should be closed when draining");
    }

    @Test
    void should_acceptConnection_whenNotDraining() {
        MaxConnectionsHandler handler = new MaxConnectionsHandler(0);

        EmbeddedChannel channel = new EmbeddedChannel(handler);

        assertTrue(channel.isOpen(), "Channel should remain open when not draining");

        channel.close();
    }
}
