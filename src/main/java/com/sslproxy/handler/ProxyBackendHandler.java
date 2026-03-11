package com.sslproxy.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * 代理后端处理器
 * <p>
 * 处理从后端服务器接收的响应，并转发回客户端。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class ProxyBackendHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(ProxyBackendHandler.class);

    private final Channel inboundChannel;
    private long responseStartTime;

    /**
     * 构造后端处理器
     * 
     * @param inboundChannel 入站通道（客户端连接）
     */
    public ProxyBackendHandler(Channel inboundChannel) {
        this.inboundChannel = inboundChannel;
    }

    /**
     * Channel 激活时调用
     * 
     * @param ctx Channel 上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        logger.debug("Backend channel active: {}", ctx.channel().remoteAddress());
        responseStartTime = System.currentTimeMillis();
        ctx.read();
    }

    /**
     * 读取数据
     * <p>
     * 接收从后端服务器返回的数据，并转发给客户端。
     * </p>
     * 
     * @param ctx Channel 上下文
     * @param msg 消息对象
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 转发后端响应到客户端
        if (inboundChannel.isActive()) {
            inboundChannel.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    // 仅当前端 Channel 可写时才继续读取后端数据（背压机制）
                    if (inboundChannel.isWritable()) {
                        ctx.channel().read();
                    }
                    // 当 inboundChannel 再次可写时，ProxyFrontendHandler.channelWritabilityChanged
                    // 会将此 channel 的 autoRead 设为 true，自动恢复读取
                } else {
                    logger.error("Failed to forward response to client: {}",
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    // writeAndFlush 失败时 Netty 会自动释放 msg
                    // 关闭前端和后端连接
                    future.channel().close();
                    ctx.close();
                }
            });
        } else {
            logger.warn("Inbound channel is not active, closing backend channel");
            // 释放消息，防止内存泄漏
            ReferenceCountUtil.release(msg);
            ctx.close();
        }
    }

    /**
     * 读取完成
     * 
     * @param ctx Channel 上下文
     */
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        long duration = System.currentTimeMillis() - responseStartTime;
        logger.debug("Backend response forwarded to client (duration: {}ms)", duration);
        ctx.flush();
    }

    /**
     * Channel 不活跃时调用
     * 
     * @param ctx Channel 上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("Backend channel inactive: {}", ctx.channel().remoteAddress());
        closeInboundChannel();
    }

    /**
     * 异常处理
     * 
     * @param ctx Channel 上下文
     * @param cause 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception in proxy backend handler: {}", cause.getMessage(), cause);
        ctx.close();
        closeInboundChannel();
    }

    /**
     * 关闭入站连接
     */
    private void closeInboundChannel() {
        if (inboundChannel != null && inboundChannel.isActive()) {
            logger.debug("Closing inbound channel");
            inboundChannel.close();
        }
    }
}

