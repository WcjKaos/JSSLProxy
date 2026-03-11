package com.sslproxy.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * NetworkUtil 单元测试
 */
class NetworkUtilTest {

    // ========== isValidPort 测试 ==========

    @Test
    void testIsValidPort_ValidPorts() {
        assertTrue(NetworkUtil.isValidPort(1));
        assertTrue(NetworkUtil.isValidPort(80));
        assertTrue(NetworkUtil.isValidPort(443));
        assertTrue(NetworkUtil.isValidPort(8080));
        assertTrue(NetworkUtil.isValidPort(65535));
    }

    @Test
    void testIsValidPort_InvalidPorts() {
        assertFalse(NetworkUtil.isValidPort(0));
        assertFalse(NetworkUtil.isValidPort(-1));
        assertFalse(NetworkUtil.isValidPort(-100));
        assertFalse(NetworkUtil.isValidPort(65536));
        assertFalse(NetworkUtil.isValidPort(100000));
    }

    // ========== extractHost 测试 ==========

    @Test
    void testExtractHost_HostPort() {
        assertEquals("192.168.1.1", NetworkUtil.extractHost("192.168.1.1:8080"));
        assertEquals("example.com", NetworkUtil.extractHost("example.com:443"));
        assertEquals("localhost", NetworkUtil.extractHost("localhost:3000"));
    }

    @Test
    void testExtractHost_WithLeadingSlash() {
        assertEquals("192.168.1.1", NetworkUtil.extractHost("/192.168.1.1:8080"));
        assertEquals("127.0.0.1", NetworkUtil.extractHost("/127.0.0.1:443"));
    }

    @Test
    void testExtractHost_IPv6() {
        // IPv6 地址通常用方括号包围，但这里测试简单情况
        assertEquals("::1", NetworkUtil.extractHost("::1:8080"));
    }

    @Test
    void testExtractHost_NoPort() {
        assertEquals("192.168.1.1", NetworkUtil.extractHost("192.168.1.1"));
        assertEquals("example.com", NetworkUtil.extractHost("example.com"));
    }

    @Test
    void testExtractHost_NullOrEmpty() {
        assertEquals("unknown", NetworkUtil.extractHost(null));
        assertEquals("unknown", NetworkUtil.extractHost(""));
    }

    // ========== extractPort 测试 ==========

    @Test
    void testExtractPort_ValidHostPort() {
        assertEquals(8080, NetworkUtil.extractPort("192.168.1.1:8080"));
        assertEquals(443, NetworkUtil.extractPort("example.com:443"));
        assertEquals(3000, NetworkUtil.extractPort("localhost:3000"));
    }

    @Test
    void testExtractPort_WithLeadingSlash() {
        assertEquals(8080, NetworkUtil.extractPort("/192.168.1.1:8080"));
        assertEquals(443, NetworkUtil.extractPort("/127.0.0.1:443"));
    }

    @Test
    void testExtractPort_NoPort() {
        assertEquals(-1, NetworkUtil.extractPort("192.168.1.1"));
        assertEquals(-1, NetworkUtil.extractPort("example.com"));
    }

    @Test
    void testExtractPort_NullOrEmpty() {
        assertEquals(-1, NetworkUtil.extractPort(null));
        assertEquals(-1, NetworkUtil.extractPort(""));
    }

    @Test
    void testExtractPort_InvalidPort() {
        assertEquals(-1, NetworkUtil.extractPort("example.com:abc"));
        assertEquals(-1, NetworkUtil.extractPort("example.com:"));
    }

    // ========== formatBytes 测试 ==========

    @Test
    void testFormatBytes_Bytes() {
        assertEquals("0 B", NetworkUtil.formatBytes(0));
        assertEquals("100 B", NetworkUtil.formatBytes(100));
        assertEquals("1023 B", NetworkUtil.formatBytes(1023));
    }

    @Test
    void testFormatBytes_Kilobytes() {
        assertEquals("1.00 KB", NetworkUtil.formatBytes(1024));
        assertEquals("1.50 KB", NetworkUtil.formatBytes(1536));
        assertEquals("1023.00 KB", NetworkUtil.formatBytes(1024 * 1023));
    }

    @Test
    void testFormatBytes_Megabytes() {
        assertEquals("1.00 MB", NetworkUtil.formatBytes(1024 * 1024));
        assertEquals("10.00 MB", NetworkUtil.formatBytes(10 * 1024 * 1024));
        assertEquals("100.50 MB", NetworkUtil.formatBytes((long) (100.5 * 1024 * 1024)));
    }

    @Test
    void testFormatBytes_Gigabytes() {
        assertEquals("1.00 GB", NetworkUtil.formatBytes(1024L * 1024 * 1024));
        assertEquals("10.00 GB", NetworkUtil.formatBytes(10L * 1024 * 1024 * 1024));
    }

    // ========== isLocalAddress 测试 ==========

    @Test
    void testIsLocalAddress_LocalIPv4() {
        assertTrue(NetworkUtil.isLocalAddress("127.0.0.1"));
        assertTrue(NetworkUtil.isLocalAddress("localhost"));
    }

    @Test
    void testIsLocalAddress_LocalIPv6() {
        assertTrue(NetworkUtil.isLocalAddress("::1"));
        assertTrue(NetworkUtil.isLocalAddress("0:0:0:0:0:0:0:1"));
    }

    @Test
    void testIsLocalAddress_NonLocal() {
        assertFalse(NetworkUtil.isLocalAddress("192.168.1.1"));
        assertFalse(NetworkUtil.isLocalAddress("10.0.0.1"));
        assertFalse(NetworkUtil.isLocalAddress("8.8.8.8"));
        assertFalse(NetworkUtil.isLocalAddress("example.com"));
    }

    @Test
    void testIsLocalAddress_Null() {
        assertFalse(NetworkUtil.isLocalAddress(null));
    }

    // ========== resolveHostname 测试 ==========

    @Test
    void testResolveHostname_Localhost() {
        String ip = NetworkUtil.resolveHostname("localhost");
        assertNotNull(ip);
        assertTrue(ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
    }

    @Test
    void testResolveHostname_InvalidHostname() {
        String ip = NetworkUtil.resolveHostname("this-host-does-not-exist-12345.invalid");
        // 部分 DNS 会将不存在的域名劫持到某个 IP（DNS 污染），因此不强制断言 null
        // 只要不抛异常即可；如果 DNS 正常，应返回 null
        if (ip != null) {
            assertFalse(ip.isEmpty(), "Resolved IP should not be empty if non-null");
        }
    }

    // ========== isHostReachable 测试 ==========

    @Test
    void testIsHostReachable_UnreachableHost() {
        // 使用一个不存在的端口，应该连接失败
        boolean reachable = NetworkUtil.isHostReachable("127.0.0.1", 59999, 100);
        assertFalse(reachable);
    }

    @Test
    void testIsHostReachable_InvalidHost() {
        boolean reachable = NetworkUtil.isHostReachable("invalid-host-12345.local", 80, 100);
        assertFalse(reachable);
    }
}
