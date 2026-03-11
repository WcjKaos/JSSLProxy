package com.sslproxy.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DynamicCertificateManager 单元测试
 */
class DynamicCertificateManagerTest {

    private DynamicCertificateManager manager;

    @BeforeEach
    void setUp() throws Exception {
        manager = new DynamicCertificateManager();
    }

    // ========== 白名单测试 ==========

    @Test
    void testAddToWhitelist() {
        String fingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99";

        boolean result = manager.addToWhitelist(fingerprint);

        assertTrue(result);
        assertTrue(manager.isInWhitelist(fingerprint));
        assertEquals(1, manager.getWhitelistSize());
    }

    @Test
    void testAddToWhitelistNormalizesCase() {
        String lowercase = "aa:bb:cc:dd:ee:ff";
        String uppercase = "AA:BB:CC:DD:EE:FF";

        manager.addToWhitelist(lowercase);

        // 应该都能找到（大小写不敏感）
        assertTrue(manager.isInWhitelist(lowercase));
        assertTrue(manager.isInWhitelist(uppercase));
    }

    @Test
    void testAddToWhitelistWithNull() {
        boolean result = manager.addToWhitelist(null);

        assertFalse(result);
        assertEquals(0, manager.getWhitelistSize());
    }

    @Test
    void testAddToWhitelistWithEmpty() {
        boolean result = manager.addToWhitelist("");

        assertFalse(result);
        assertEquals(0, manager.getWhitelistSize());
    }

    @Test
    void testRemoveFromWhitelist() {
        String fingerprint = "11:22:33:44:55:66:77:88";
        manager.addToWhitelist(fingerprint);

        boolean result = manager.removeFromWhitelist(fingerprint);

        assertTrue(result);
        assertFalse(manager.isInWhitelist(fingerprint));
        assertEquals(0, manager.getWhitelistSize());
    }

    @Test
    void testRemoveFromWhitelistNotFound() {
        boolean result = manager.removeFromWhitelist("NON:EXISTENT:FP");

        assertFalse(result);
    }

    @Test
    void testRemoveFromWhitelistWithNull() {
        boolean result = manager.removeFromWhitelist(null);

        assertFalse(result);
    }

    @Test
    void testGetWhitelistReturnsImmutableCopy() {
        manager.addToWhitelist("FP:1");
        manager.addToWhitelist("FP:2");

        Set<String> whitelist = manager.getWhitelist();

        assertEquals(2, whitelist.size());
        // 返回的应该是不可变副本
        assertThrows(UnsupportedOperationException.class, () -> whitelist.add("FP:3"));
    }

    @Test
    void testClearWhitelist() {
        manager.addToWhitelist("FP:1");
        manager.addToWhitelist("FP:2");
        manager.addToWhitelist("FP:3");

        manager.clearWhitelist();

        assertEquals(0, manager.getWhitelistSize());
    }

    // ========== 黑名单测试 ==========

    @Test
    void testAddToBlacklist() {
        String fingerprint = "FF:EE:DD:CC:BB:AA:99:88";

        boolean result = manager.addToBlacklist(fingerprint);

        assertTrue(result);
        assertTrue(manager.isInBlacklist(fingerprint));
        assertEquals(1, manager.getBlacklistSize());
    }

    @Test
    void testAddToBlacklistNormalizesCase() {
        String lowercase = "ff:ee:dd:cc:bb:aa";
        String uppercase = "FF:EE:DD:CC:BB:AA";

        manager.addToBlacklist(lowercase);

        assertTrue(manager.isInBlacklist(lowercase));
        assertTrue(manager.isInBlacklist(uppercase));
    }

    @Test
    void testAddToBlacklistWithNull() {
        boolean result = manager.addToBlacklist(null);

        assertFalse(result);
        assertEquals(0, manager.getBlacklistSize());
    }

    @Test
    void testAddToBlacklistWithEmpty() {
        boolean result = manager.addToBlacklist("");

        assertFalse(result);
        assertEquals(0, manager.getBlacklistSize());
    }

    @Test
    void testRemoveFromBlacklist() {
        String fingerprint = "88:77:66:55:44:33";
        manager.addToBlacklist(fingerprint);

        boolean result = manager.removeFromBlacklist(fingerprint);

        assertTrue(result);
        assertFalse(manager.isInBlacklist(fingerprint));
        assertEquals(0, manager.getBlacklistSize());
    }

    @Test
    void testRemoveFromBlacklistNotFound() {
        boolean result = manager.removeFromBlacklist("NON:EXISTENT:FP");

        assertFalse(result);
    }

    @Test
    void testGetBlacklistReturnsImmutableCopy() {
        manager.addToBlacklist("FP:A");
        manager.addToBlacklist("FP:B");

        Set<String> blacklist = manager.getBlacklist();

        assertEquals(2, blacklist.size());
        assertThrows(UnsupportedOperationException.class, () -> blacklist.add("FP:C"));
    }

    @Test
    void testClearBlacklist() {
        manager.addToBlacklist("FP:A");
        manager.addToBlacklist("FP:B");

        manager.clearBlacklist();

        assertEquals(0, manager.getBlacklistSize());
    }

    // ========== 边界条件测试 ==========

    @Test
    void testWhitelistAndBlacklistAreIndependent() {
        String fingerprint = "COMMON:FP:123";

        manager.addToWhitelist(fingerprint);
        manager.addToBlacklist(fingerprint);

        // 同一个指纹可以同时在白名单和黑名单中
        assertTrue(manager.isInWhitelist(fingerprint));
        assertTrue(manager.isInBlacklist(fingerprint));

        manager.removeFromWhitelist(fingerprint);

        // 从白名单移除不影响黑名单
        assertFalse(manager.isInWhitelist(fingerprint));
        assertTrue(manager.isInBlacklist(fingerprint));
    }

    @Test
    void testIsInWhitelistWithNull() {
        assertFalse(manager.isInWhitelist(null));
    }

    @Test
    void testIsInBlacklistWithNull() {
        assertFalse(manager.isInBlacklist(null));
    }

    @Test
    void testTrimsWhitespace() {
        String fingerprint = "  AA:BB:CC:DD  ";
        manager.addToWhitelist(fingerprint);

        assertTrue(manager.isInWhitelist("AA:BB:CC:DD"));
        assertTrue(manager.isInWhitelist("  AA:BB:CC:DD  "));
    }

    @Test
    void testDuplicateAdditions() {
        String fingerprint = "SAME:FP:123";

        manager.addToWhitelist(fingerprint);
        manager.addToWhitelist(fingerprint);
        manager.addToWhitelist(fingerprint);

        // 应该只有一个
        assertEquals(1, manager.getWhitelistSize());
    }

    // ========== 持久化测试（T-11）==========

    @Nested
    class PersistenceTests {

        @TempDir
        Path tempDir;

        Path persistenceFile;

        @BeforeEach
        void setPath() {
            manager = new DynamicCertificateManager();
            persistenceFile = tempDir.resolve("dynamic-lists.json");
            manager.setPersistencePath(persistenceFile);
        }

        @Test
        void should_writeFileOnBlacklistAdd() throws IOException {
            manager.addToBlacklist("AABBCC:DDEEFF");

            assertTrue(Files.exists(persistenceFile), "Persistence file should exist after add");
            String content = Files.readString(persistenceFile);
            assertTrue(content.contains("AABBCC"), "File should contain fingerprint");
            assertTrue(content.contains("lastUpdated"), "File should contain timestamp");
        }

        @Test
        void should_writeFileOnWhitelistAdd() throws IOException {
            manager.addToWhitelist("112233:445566");

            assertTrue(Files.exists(persistenceFile));
            String content = Files.readString(persistenceFile);
            assertTrue(content.contains("112233"));
        }

        @Test
        void should_loadBlacklistFromDisk_onSetPersistencePath() {
            // 先写入一条黑名单
            manager.addToBlacklist("PERSIST:FP:01");
            assertEquals(1, manager.getBlacklistSize());

            // 清空内存，重新设置路径（模拟重启加载）
            manager.clearBlacklist();
            assertEquals(0, manager.getBlacklistSize());

            manager.setPersistencePath(persistenceFile); // 触发 loadFromDisk
            assertEquals(1, manager.getBlacklistSize());
            assertTrue(manager.isInBlacklist("PERSIST:FP:01"));
        }

        @Test
        void should_loadBothListsFromDisk() {
            manager.addToBlacklist("BL:FP:01");
            manager.addToWhitelist("WL:FP:01");

            manager.clearBlacklist();
            manager.clearWhitelist();

            manager.setPersistencePath(persistenceFile);

            assertEquals(1, manager.getBlacklistSize());
            assertEquals(1, manager.getWhitelistSize());
            assertTrue(manager.isInBlacklist("BL:FP:01"));
            assertTrue(manager.isInWhitelist("WL:FP:01"));
        }

        @Test
        void should_updateFileOnRemove() throws IOException {
            manager.addToBlacklist("REMOVE:ME:01");
            manager.addToBlacklist("KEEP:ME:01");

            manager.removeFromBlacklist("REMOVE:ME:01");

            String content = Files.readString(persistenceFile);
            assertFalse(content.contains("REMOVE:ME:01"), "Removed fingerprint should not be in file");
            assertTrue(content.contains("KEEP:ME:01"), "Kept fingerprint should remain in file");
        }

        @Test
        void should_notThrow_when_persistencePathIsNull() {
            // 禁用持久化后，add/remove 不应抛出异常
            manager.setPersistencePath(null);
            assertDoesNotThrow(() -> {
                manager.addToBlacklist("FP:NO:PERSIST");
                manager.removeFromBlacklist("FP:NO:PERSIST");
            });
            assertFalse(Files.exists(persistenceFile), "No file should be written when path is null");
        }

        @Test
        void should_degradeGracefully_when_directoryNotWritable() {
            // 指向不存在目录下的文件，写入会失败但不应抛出异常
            Path badPath = tempDir.resolve("nonexistent-dir/dynamic-lists.json");
            manager.setPersistencePath(badPath);

            assertDoesNotThrow(() -> manager.addToBlacklist("FP:BAD:PATH"));
            // 内存状态仍然正确
            assertTrue(manager.isInBlacklist("FP:BAD:PATH"));
        }
    }
}
