package com.sslproxy.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.config.SSLContextFactory;
import com.sslproxy.handler.ProxyBackendHandler;
import com.sslproxy.loadbalance.LoadBalancer.Backend;
import com.sslproxy.loadbalance.LoadBalancerManager;
import com.sslproxy.util.TlsUtil;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

/**
 * 后端连接器
 * <p>
 * 负责建立到目标后端服务器的连接，支持 HTTP 和 HTTPS 协议。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class BackendConnector {
    private static final Logger logger = LoggerFactory.getLogger(BackendConnector.class);

    private final ProxyConfig config;
    private final LoadBalancerManager loadBalancerManager;
    private volatile SslContext clientSslContext;

    /**
     * 构造后端连接器
     *
     * @param config              代理配置
     * @param loadBalancerManager 负载均衡管理器（可为 null）
     */
    public BackendConnector(ProxyConfig config, LoadBalancerManager loadBalancerManager) {
        this.config = config;
        this.loadBalancerManager = loadBalancerManager;

        if (needsClientSslContext(config)) {
            try {
                this.clientSslContext = SSLContextFactory.createClientSslContext(config.ssl);
                logger.debug("Client SSL context initialized for HTTPS backend");
            } catch (Exception e) {
                logger.error("Failed to initialize client SSL context, HTTPS backend connections will be refused: {}", e.getMessage(), e);
                // 不静默降级为明文，保留 clientSslContext 为 null，后续连接时检查并拒绝
            }
        }
    }

    /**
     * 连接到后端服务器
     * <p>
     * 如果配置了负载均衡器，则从负载均衡器选择后端；
     * 否则使用配置中的单后端目标。
     * </p>
     *
     * @param inboundChannel 入站通道（客户端连接）
     * @return ChannelFuture 对象
     */
    public ChannelFuture connect(Channel inboundChannel) {
        // 检查是否有负载均衡器
        LoadBalancerManager lbManager = loadBalancerManager;
        if (lbManager != null) {
            Backend backend = lbManager.selectBackend();
            if (backend != null) {
                return connectToBackend(inboundChannel, backend.getHost(), backend.getPort(), backend.getProtocol(), backend);
            } else {
                // 没有可用的健康后端，尝试使用配置中的默认目标
                logger.warn("No healthy backends available, falling back to default target");
            }
        }

        // 使用配置中的单后端目标
        return connectToBackend(inboundChannel, config.target.host, config.target.port, config.target.protocol, null);
    }

    /**
     * 连接到指定的后端服务器
     *
     * @param inboundChannel 入站通道
     * @param host           后端主机
     * @param port           后端端口
     * @param protocol       后端协议（http/https）
     * @param backend        后端对象（用于连接计数，可为 null）
     * @return ChannelFuture 对象
     */
    public ChannelFuture connectToBackend(Channel inboundChannel, String host, int port, String protocol, Backend backend) {
        Bootstrap bootstrap = new Bootstrap();

        // 从配置读取连接参数
        int timeout = (config.connection != null && config.connection.timeout > 0)
                ? config.connection.timeout : 30000;
        boolean keepalive = config.connection == null || config.connection.keepalive;

        final String targetHost = host;
        final int targetPort = port;
        final String targetProtocol = protocol != null ? protocol : "http";

        bootstrap.group(inboundChannel.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout)
                .option(ChannelOption.SO_KEEPALIVE, keepalive)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // 如果目标是 HTTPS，添加 SSL Handler
                        if ("https".equalsIgnoreCase(targetProtocol)) {
                            if (clientSslContext == null) {
                                clientSslContext = createClientSslContext();
                            }
                            if (clientSslContext == null) {
                                throw new IllegalStateException(
                                        "SSL context unavailable for HTTPS backend " + targetHost + ":" + targetPort +
                                        ". Refusing to connect in plaintext.");
                            }
                            SslHandler sslHandler = clientSslContext.newHandler(ch.alloc(), targetHost, targetPort);
                            TlsUtil.enableHostnameVerification(sslHandler.engine());
                            pipeline.addLast("ssl", sslHandler);
                            logger.debug("SSL handler added for HTTPS backend connection");
                        }

                        // HTTP 编解码器
                        pipeline.addLast("http-codec", new HttpClientCodec());

                        // HTTP 消息聚合器（使用配置的最大请求大小）
                        int maxRequestSize = config.performance != null && config.performance.maxRequestSize > 0
                                ? config.performance.maxRequestSize
                                : 10 * 1024 * 1024;  // 默认 10MB
                        pipeline.addLast("http-aggregator", new HttpObjectAggregator(maxRequestSize));

                        // 后端处理器
                        pipeline.addLast("proxy-backend", new ProxyBackendHandler(inboundChannel));
                    }
                });

        logger.debug("Connecting to backend: {}:{} ({})", targetHost, targetPort, targetProtocol);

        ChannelFuture connectFuture = bootstrap.connect(targetHost, targetPort);

        // 如果有 backend 对象，添加连接计数监听器
        if (backend != null) {
            backend.incrementConnections();
            connectFuture.addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    // 连接失败，减少连接计数
                    backend.decrementConnections();
                } else {
                    // 连接成功，在通道关闭时减少计数
                    future.channel().closeFuture().addListener(closeFuture -> backend.decrementConnections());
                }
            });
        }

        return connectFuture;
    }

    private boolean needsClientSslContext(ProxyConfig config) {
        if (config == null || config.target == null) {
            return false;
        }
        if ("https".equalsIgnoreCase(config.target.protocol)) {
            return true;
        }
        if (config.target.backends != null) {
            for (ProxyConfig.BackendConfig backend : config.target.backends) {
                if (backend != null && "https".equalsIgnoreCase(backend.protocol)) {
                    return true;
                }
            }
        }
        return false;
    }

    private SslContext createClientSslContext() {
        try {
            return SSLContextFactory.createClientSslContext(config.ssl);
        } catch (Exception e) {
            logger.error("Failed to initialize client SSL context: {}", e.getMessage(), e);
            return null;
        }
    }
}
