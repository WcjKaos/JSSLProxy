package com.sslproxy.util;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网络工具类
 * <p>
 * 提供网络相关的实用方法，包括连接性检查、地址解析等。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class NetworkUtil {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtil.class);

    /**
     * 检查指定主机和端口是否可达
     * 
     * @param host 主机名或 IP 地址
     * @param port 端口号
     * @param timeoutMs 超时时间（毫秒）
     * @return true 如果可以连接，false 如果连接失败
     */
    public static boolean isHostReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            logger.debug("Host {}:{} is not reachable: {}", host, port, e.getMessage());
            return false;
        }
    }

    /**
     * 解析主机名到 IP 地址
     * 
     * @param hostname 主机名
     * @return IP 地址字符串，如果解析失败返回 null
     */
    public static String resolveHostname(String hostname) {
        try {
            InetAddress address = InetAddress.getByName(hostname);
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            logger.warn("Failed to resolve hostname: {}", hostname);
            return null;
        }
    }

    /**
     * 验证端口号是否有效
     * 
     * @param port 端口号
     * @return true 如果端口号在有效范围内 (1-65535)
     */
    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    /**
     * 从远程地址字符串提取主机名
     * 
     * @param remoteAddress 远程地址字符串（格式: "host:port" 或 "/ip:port"）
     * @return 主机名或 IP 地址
     */
    public static String extractHost(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isEmpty()) {
            return "unknown";
        }
        // 移除开头的 "/"
        if (remoteAddress.startsWith("/")) {
            remoteAddress = remoteAddress.substring(1);
        }
        // 提取冒号前的部分
        int colonIndex = remoteAddress.lastIndexOf(':');
        if (colonIndex > 0) {
            return remoteAddress.substring(0, colonIndex);
        }
        return remoteAddress;
    }

    /**
     * 从远程地址字符串提取端口号
     * 
     * @param remoteAddress 远程地址字符串（格式: "host:port" 或 "/ip:port"）
     * @return 端口号，如果提取失败返回 -1
     */
    public static int extractPort(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isEmpty()) {
            return -1;
        }
        int colonIndex = remoteAddress.lastIndexOf(':');
        if (colonIndex > 0 && colonIndex < remoteAddress.length() - 1) {
            try {
                return Integer.parseInt(remoteAddress.substring(colonIndex + 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * 格式化字节数为人类可读的格式
     * 
     * @param bytes 字节数
     * @return 格式化的字符串（如 "1.5 MB"）
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * 检查 IP 地址是否为本地地址
     * 
     * @param ip IP 地址字符串
     * @return true 如果是本地地址
     */
    public static boolean isLocalAddress(String ip) {
        return ip != null && (ip.equals("127.0.0.1") || 
                              ip.equals("localhost") || 
                              ip.equals("0:0:0:0:0:0:0:1") || 
                              ip.equals("::1"));
    }
}

