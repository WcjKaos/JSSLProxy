package com.sslproxy.security;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态证书管理器
 * <p>
 * 支持运行时动态管理证书白名单和黑名单。
 * 线程安全，可通过管理 API 进行操作。
 * </p>
 *
 * @author SSL Proxy Team
 * @version 1.0
 */
public class DynamicCertificateManager {
    private static final Logger logger = LoggerFactory.getLogger(DynamicCertificateManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 动态白名单（运行时添加的） */
    private final Set<String> dynamicWhitelist;

    /** 动态黑名单（运行时添加的） */
    private final Set<String> dynamicBlacklist;

    /** 关联的 ClientAuthenticator */
    private volatile ClientAuthenticator authenticator;

    /** 持久化文件路径，null 表示不持久化 */
    private volatile Path persistencePath = null;

    /** 持久化文件的 JSON 数据模型 */
    private static class PersistedData {
        Set<String> blacklist;
        Set<String> whitelist;
        long lastUpdated;
    }

    public DynamicCertificateManager() {
        this.dynamicWhitelist = ConcurrentHashMap.newKeySet();
        this.dynamicBlacklist = ConcurrentHashMap.newKeySet();
        logger.info("DynamicCertificateManager initialized");
    }

    /**
     * 设置持久化文件路径并立即从磁盘加载已有数据。
     * 传入 null 禁用持久化。
     *
     * @param path 持久化文件路径，null 表示禁用
     */
    public void setPersistencePath(Path path) {
        this.persistencePath = path;
        if (path != null) {
            loadFromDisk();
        }
    }

    /**
     * 从磁盘加载持久化的名单数据
     */
    private void loadFromDisk() {
        Path path = this.persistencePath;
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            String json = Files.readString(path);
            PersistedData data = GSON.fromJson(json, PersistedData.class);
            if (data != null) {
                if (data.blacklist != null) {
                    dynamicBlacklist.addAll(data.blacklist);
                }
                if (data.whitelist != null) {
                    dynamicWhitelist.addAll(data.whitelist);
                }
                logger.info("Loaded dynamic lists from {}: {} blacklist, {} whitelist entries",
                        path, dynamicBlacklist.size(), dynamicWhitelist.size());
            }
        } catch (Exception e) {
            logger.error("Failed to load dynamic lists from {}: {}", path, e.getMessage(), e);
        }
    }

    /**
     * 将当前名单原子写入磁盘。
     * 写入失败时记录 ERROR 日志但不回滚内存状态。
     */
    private synchronized void persistToDisk() {
        Path path = this.persistencePath;
        if (path == null) {
            return;
        }
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            PersistedData data = new PersistedData();
            data.blacklist = Set.copyOf(dynamicBlacklist);
            data.whitelist = Set.copyOf(dynamicWhitelist);
            data.lastUpdated = System.currentTimeMillis();

            Files.writeString(tmpPath, GSON.toJson(data));

            try {
                Files.move(tmpPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicEx) {
                // ATOMIC_MOVE 在部分平台（如 Windows 跨设备）不支持，fallback
                Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
            logger.debug("Persisted dynamic lists to {}: {} blacklist, {} whitelist entries",
                    path, dynamicBlacklist.size(), dynamicWhitelist.size());
        } catch (Exception e) {
            logger.error("Failed to persist dynamic lists to {}: {}", path, e.getMessage(), e);
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 设置关联的 ClientAuthenticator
     *
     * @param authenticator ClientAuthenticator 实例
     */
    public void setAuthenticator(ClientAuthenticator authenticator) {
        this.authenticator = authenticator;
        logger.debug("ClientAuthenticator associated with DynamicCertificateManager");

        if (this.authenticator != null) {
            // Re-apply dynamic state to the new authenticator (e.g., after SSL context reload).
            for (String fp : dynamicWhitelist) {
                this.authenticator.addToWhitelist(fp);
            }
            for (String fp : dynamicBlacklist) {
                this.authenticator.addToBlacklist(fp);
            }
        }
    }

    /**
     * 添加证书指纹到白名单
     *
     * @param fingerprint SHA-256 指纹
     * @return true 如果添加成功
     */
    public boolean addToWhitelist(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }

        String normalized = fingerprint.toUpperCase().trim();
        dynamicWhitelist.add(normalized);

        // 同步到 ClientAuthenticator
        if (authenticator != null) {
            authenticator.addToWhitelist(normalized);
        }

        logger.info("Certificate added to dynamic whitelist: {}", truncateFingerprint(normalized));
        persistToDisk();
        return true;
    }

    /**
     * 从白名单移除证书指纹
     *
     * @param fingerprint SHA-256 指纹
     * @return true 如果移除成功
     */
    public boolean removeFromWhitelist(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }

        String normalized = fingerprint.toUpperCase().trim();
        boolean removed = dynamicWhitelist.remove(normalized);

        // 同步到 ClientAuthenticator
        if (authenticator != null && removed) {
            authenticator.removeFromWhitelist(normalized);
        }

        if (removed) {
            logger.info("Certificate removed from dynamic whitelist: {}", truncateFingerprint(normalized));
            persistToDisk();
        }
        return removed;
    }

    /**
     * 添加证书指纹到黑名单
     *
     * @param fingerprint SHA-256 指纹
     * @return true 如果添加成功
     */
    public boolean addToBlacklist(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }

        String normalized = fingerprint.toUpperCase().trim();
        dynamicBlacklist.add(normalized);

        // 同步到 ClientAuthenticator
        if (authenticator != null) {
            authenticator.addToBlacklist(normalized);
        }

        logger.warn("Certificate added to dynamic blacklist: {}", truncateFingerprint(normalized));
        persistToDisk();
        return true;
    }

    /**
     * 从黑名单移除证书指纹
     *
     * @param fingerprint SHA-256 指纹
     * @return true 如果移除成功
     */
    public boolean removeFromBlacklist(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }

        String normalized = fingerprint.toUpperCase().trim();
        boolean removed = dynamicBlacklist.remove(normalized);

        // 同步到 ClientAuthenticator
        if (authenticator != null && removed) {
            authenticator.removeFromBlacklist(normalized);
        }

        if (removed) {
            logger.info("Certificate removed from dynamic blacklist: {}", truncateFingerprint(normalized));
            persistToDisk();
        }
        return removed;
    }

    /**
     * 检查指纹是否在动态白名单中
     *
     * @param fingerprint SHA-256 指纹
     * @return true 如果在白名单中
     */
    public boolean isInWhitelist(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        return dynamicWhitelist.contains(fingerprint.toUpperCase().trim());
    }

    /**
     * 检查指纹是否在动态黑名单中
     *
     * @param fingerprint SHA-256 指纹
     * @return true 如果在黑名单中
     */
    public boolean isInBlacklist(String fingerprint) {
        if (fingerprint == null) {
            return false;
        }
        return dynamicBlacklist.contains(fingerprint.toUpperCase().trim());
    }

    /**
     * 获取动态白名单大小
     *
     * @return 白名单中的证书数量
     */
    public int getWhitelistSize() {
        return dynamicWhitelist.size();
    }

    /**
     * 获取动态黑名单大小
     *
     * @return 黑名单中的证书数量
     */
    public int getBlacklistSize() {
        return dynamicBlacklist.size();
    }

    /**
     * 获取动态白名单的不可变视图
     *
     * @return 白名单集合
     */
    public Set<String> getWhitelist() {
        return Set.copyOf(dynamicWhitelist);
    }

    /**
     * 获取动态黑名单的不可变视图
     *
     * @return 黑名单集合
     */
    public Set<String> getBlacklist() {
        return Set.copyOf(dynamicBlacklist);
    }

    /**
     * 清空动态白名单
     */
    public void clearWhitelist() {
        // 先从 authenticator 中移除所有动态添加的白名单条目
        ClientAuthenticator auth = this.authenticator;
        if (auth != null) {
            for (String fp : dynamicWhitelist) {
                auth.removeFromWhitelist(fp);
            }
        }
        dynamicWhitelist.clear();
        logger.info("Dynamic whitelist cleared");
    }

    /**
     * 清空动态黑名单
     */
    public void clearBlacklist() {
        // 先从 authenticator 中移除所有动态添加的黑名单条目
        ClientAuthenticator auth = this.authenticator;
        if (auth != null) {
            for (String fp : dynamicBlacklist) {
                auth.removeFromBlacklist(fp);
            }
        }
        dynamicBlacklist.clear();
        logger.info("Dynamic blacklist cleared");
    }

    /**
     * 截断指纹用于日志显示
     */
    private String truncateFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() <= 16) {
            return fingerprint;
        }
        return fingerprint.substring(0, 16) + "...";
    }
}
