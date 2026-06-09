package com.sslproxy.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Netty 传输层工厂
 * <p>
 * 根据操作系统自动选择最佳的传输实现：
 * <ul>
 *   <li>Linux: 使用 Epoll（需要 netty-transport-native-epoll）</li>
 *   <li>macOS: 使用 KQueue（需要 netty-transport-native-kqueue）</li>
 *   <li>Windows/其他: 使用 NIO</li>
 * </ul>
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class TransportFactory {
    private static final Logger logger = LoggerFactory.getLogger(TransportFactory.class);

    /** 传输类型 */
    public enum TransportType {
        NIO("Java NIO"),
        EPOLL("Linux Epoll"),
        KQUEUE("macOS KQueue");

        private final String description;

        TransportType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private static volatile TransportType selectedTransport;
    private static volatile boolean initialized = false;

    // 检测是否支持 Epoll
    private static boolean isEpollAvailable() {
        try {
            Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
            return (Boolean) epollClass.getMethod("isAvailable").invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    // 检测是否支持 KQueue
    private static boolean isKQueueAvailable() {
        try {
            Class<?> kqueueClass = Class.forName("io.netty.channel.kqueue.KQueue");
            return (Boolean) kqueueClass.getMethod("isAvailable").invoke(null);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 初始化并检测可用的传输类型
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("linux") && isEpollAvailable()) {
            selectedTransport = TransportType.EPOLL;
            logger.info("Native transport detected: {} (Linux Epoll)", selectedTransport.getDescription());
        } else if ((osName.contains("mac") || osName.contains("darwin")) && isKQueueAvailable()) {
            selectedTransport = TransportType.KQUEUE;
            logger.info("Native transport detected: {} (macOS KQueue)", selectedTransport.getDescription());
        } else {
            selectedTransport = TransportType.NIO;
            if (osName.contains("linux")) {
                logger.info("Using {} transport (Epoll not available, ensure netty-transport-native-epoll is on classpath)",
                        selectedTransport.getDescription());
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                logger.info("Using {} transport (KQueue not available, ensure netty-transport-native-kqueue is on classpath)",
                        selectedTransport.getDescription());
            } else {
                logger.info("Using {} transport (native transport not supported on this OS: {})",
                        selectedTransport.getDescription(), osName);
            }
        }

        initialized = true;
    }

    /**
     * 获取当前选择的传输类型
     */
    public static TransportType getTransportType() {
        if (!initialized) {
            initialize();
        }
        return selectedTransport;
    }

    /**
     * 创建 EventLoopGroup
     *
     * @param nThreads 线程数，0 表示使用默认值
     * @return 适合当前平台的 EventLoopGroup
     */
    public static EventLoopGroup createEventLoopGroup(int nThreads) {
        if (!initialized) {
            initialize();
        }

        if (selectedTransport == TransportType.NIO) {
            if (nThreads > 0) {
                return new NioEventLoopGroup(nThreads);
            }
            return new NioEventLoopGroup();
        }

        IoHandlerFactory ioHandlerFactory = getIoHandlerFactory();

        if (nThreads > 0) {
            return new MultiThreadIoEventLoopGroup(nThreads, ioHandlerFactory);
        } else {
            return new MultiThreadIoEventLoopGroup(ioHandlerFactory);
        }
    }

    /**
     * 获取 IoHandlerFactory
     */
    private static IoHandlerFactory getIoHandlerFactory() {
        switch (selectedTransport) {
            case EPOLL:
                return getEpollIoHandlerFactory();
            case KQUEUE:
                return getKQueueIoHandlerFactory();
            default:
                return NioIoHandler.newFactory();
        }
    }

    /**
     * 获取 Epoll IoHandler 工厂（通过反射避免编译时依赖）
     */
    private static IoHandlerFactory getEpollIoHandlerFactory() {
        try {
            Class<?> epollIoHandlerClass = Class.forName("io.netty.channel.epoll.EpollIoHandler");
            return (IoHandlerFactory) epollIoHandlerClass.getMethod("newFactory").invoke(null);
        } catch (Exception e) {
            logger.warn("Failed to create EpollIoHandler, falling back to NIO: {}", e.getMessage());
            selectedTransport = TransportType.NIO;
            return NioIoHandler.newFactory();
        }
    }

    /**
     * 获取 KQueue IoHandler 工厂（通过反射避免编译时依赖）
     */
    private static IoHandlerFactory getKQueueIoHandlerFactory() {
        try {
            Class<?> kqueueIoHandlerClass = Class.forName("io.netty.channel.kqueue.KQueueIoHandler");
            return (IoHandlerFactory) kqueueIoHandlerClass.getMethod("newFactory").invoke(null);
        } catch (Exception e) {
            logger.warn("Failed to create KQueueIoHandler, falling back to NIO: {}", e.getMessage());
            selectedTransport = TransportType.NIO;
            return NioIoHandler.newFactory();
        }
    }

    /**
     * 获取 ServerChannel 类
     *
     * @return 适合当前平台的 ServerChannel 类
     */
    @SuppressWarnings("unchecked")
    public static Class<? extends ServerChannel> getServerChannelClass() {
        if (!initialized) {
            initialize();
        }

        switch (selectedTransport) {
            case EPOLL:
                try {
                    return (Class<? extends ServerChannel>) Class.forName(
                            "io.netty.channel.epoll.EpollServerSocketChannel");
                } catch (ClassNotFoundException e) {
                    logger.warn("EpollServerSocketChannel not found, falling back to NIO");
                    selectedTransport = TransportType.NIO;
                    return NioServerSocketChannel.class;
                }
            case KQUEUE:
                try {
                    return (Class<? extends ServerChannel>) Class.forName(
                            "io.netty.channel.kqueue.KQueueServerSocketChannel");
                } catch (ClassNotFoundException e) {
                    logger.warn("KQueueServerSocketChannel not found, falling back to NIO");
                    selectedTransport = TransportType.NIO;
                    return NioServerSocketChannel.class;
                }
            default:
                return NioServerSocketChannel.class;
        }
    }

    /**
     * 获取 SocketChannel 类（用于客户端连接）
     *
     * @return 适合当前平台的 SocketChannel 类
     */
    @SuppressWarnings("unchecked")
    public static Class<? extends io.netty.channel.socket.SocketChannel> getSocketChannelClass() {
        if (!initialized) {
            initialize();
        }

        switch (selectedTransport) {
            case EPOLL:
                try {
                    return (Class<? extends io.netty.channel.socket.SocketChannel>) Class.forName(
                            "io.netty.channel.epoll.EpollSocketChannel");
                } catch (ClassNotFoundException e) {
                    logger.warn("EpollSocketChannel not found, falling back to NIO");
                    return NioSocketChannel.class;
                }
            case KQUEUE:
                try {
                    return (Class<? extends io.netty.channel.socket.SocketChannel>) Class.forName(
                            "io.netty.channel.kqueue.KQueueSocketChannel");
                } catch (ClassNotFoundException e) {
                    logger.warn("KQueueSocketChannel not found, falling back to NIO");
                    return NioSocketChannel.class;
                }
            default:
                return NioSocketChannel.class;
        }
    }

    /**
     * 重置状态（仅用于测试）
     */
    public static synchronized void reset() {
        initialized = false;
        selectedTransport = null;
    }
}
