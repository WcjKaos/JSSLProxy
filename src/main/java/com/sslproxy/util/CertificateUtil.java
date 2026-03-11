package com.sslproxy.util;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 证书工具类
 * <p>
 * 提供证书相关的实用方法，包括证书信息提取、验证、指纹计算等。
 * </p>
 * 
 * @author SSL Proxy Team
 * @version 1.0
 */
public class CertificateUtil {

    /**
     * 检查证书是否已过期
     * 
     * @param cert X.509 证书
     * @return true 如果证书已过期，false 如果仍有效
     */
    public static boolean isCertificateExpired(X509Certificate cert) {
        if (cert == null) {
            return true;
        }
        try {
            cert.checkValidity();
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 检查证书是否即将过期（在指定天数内）
     * 
     * @param cert X.509 证书
     * @param days 天数阈值
     * @return true 如果证书将在指定天数内过期
     */
    public static boolean isCertificateExpiringSoon(X509Certificate cert, int days) {
        if (cert == null) {
            return true;
        }
        Date notAfter = cert.getNotAfter();
        long currentTime = System.currentTimeMillis();
        long expiryTime = notAfter.getTime();
        long daysInMillis = days * 24L * 60L * 60L * 1000L;
        return (expiryTime - currentTime) < daysInMillis;
    }

    /**
     * 获取证书的 SHA-256 指纹
     * 
     * @param cert X.509 证书
     * @return 十六进制格式的指纹字符串，如果失败返回 null
     */
    public static String getCertificateFingerprint(X509Certificate cert) {
        return getCertificateFingerprint(cert, "SHA-256");
    }

    /**
     * 获取证书的指纹
     * 
     * @param cert X.509 证书
     * @param algorithm 哈希算法（如 SHA-1, SHA-256）
     * @return 十六进制格式的指纹字符串，如果失败返回 null
     */
    public static String getCertificateFingerprint(X509Certificate cert, String algorithm) {
        if (cert == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] encoded = cert.getEncoded();
            byte[] digest = md.digest(encoded);
            return bytesToHex(digest);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取证书的通用名称 (Common Name, CN)
     * 
     * @param cert X.509 证书
     * @return CN 值，如果不存在返回 null
     */
    public static String getCommonName(X509Certificate cert) {
        if (cert == null) {
            return null;
        }
        String dn = cert.getSubjectX500Principal().getName();
        // 简单解析 DN 字符串，查找 CN=
        String[] parts = dn.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("CN=")) {
                return part.substring(3);
            }
        }
        return null;
    }

    /**
     * 获取证书的详细信息（用于日志记录）
     * 
     * @param cert X.509 证书
     * @return 格式化的证书信息字符串
     */
    public static String getCertificateInfo(X509Certificate cert) {
        if (cert == null) {
            return "Certificate is null";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();
        sb.append("Subject: ").append(cert.getSubjectX500Principal().getName()).append("\n");
        sb.append("Issuer: ").append(cert.getIssuerX500Principal().getName()).append("\n");
        sb.append("Serial: ").append(cert.getSerialNumber().toString(16).toUpperCase()).append("\n");
        sb.append("Valid From: ").append(sdf.format(cert.getNotBefore())).append("\n");
        sb.append("Valid Until: ").append(sdf.format(cert.getNotAfter())).append("\n");
        sb.append("Fingerprint (SHA-256): ").append(getCertificateFingerprint(cert));
        return sb.toString();
    }

    /**
     * 获取证书的简短描述（单行，用于日志）
     * 
     * @param cert X.509 证书
     * @return 简短描述字符串
     */
    public static String getCertificateSummary(X509Certificate cert) {
        if (cert == null) {
            return "null";
        }
        String cn = getCommonName(cert);
        String fingerprint = getCertificateFingerprint(cert);
        if (fingerprint != null && fingerprint.length() > 16) {
            fingerprint = fingerprint.substring(0, 16) + "...";
        }
        return String.format("CN=%s, Fingerprint=%s", 
                cn != null ? cn : "unknown", 
                fingerprint != null ? fingerprint : "unknown");
    }

    /**
     * 从 PEM 文件加载 X.509 证书
     * 
     * @param pemFilePath PEM 文件路径
     * @return X.509 证书对象
     * @throws Exception 如果加载失败
     */
    public static X509Certificate loadCertificateFromPem(String pemFilePath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (FileInputStream fis = new FileInputStream(pemFilePath)) {
            Certificate cert = cf.generateCertificate(fis);
            if (cert instanceof X509Certificate) {
                return (X509Certificate) cert;
            }
            throw new IllegalArgumentException("Not a X.509 certificate");
        }
    }

    /**
     * 从字节数组加载 X.509 证书
     *
     * @param certBytes 证书的字节数组
     * @throws Exception 如果加载失败
     */
    public static void loadCertificateFromBytes(byte[] certBytes) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream bais = new ByteArrayInputStream(certBytes);
        Certificate cert = cf.generateCertificate(bais);
        if (cert instanceof X509Certificate) {
            return;
        }
        throw new IllegalArgumentException("Not a X.509 certificate");
    }

    /**
     * 验证证书链的完整性
     * 
     * @param certChain 证书链数组
     * @return true 如果证书链有效
     */
    public static boolean validateCertificateChain(X509Certificate[] certChain) {
        if (certChain == null || certChain.length == 0) {
            return false;
        }
        try {
            for (int i = 0; i < certChain.length - 1; i++) {
                X509Certificate current = certChain[i];
                X509Certificate issuer = certChain[i + 1];
                // 验证当前证书是否由下一个证书签发
                current.verify(issuer.getPublicKey());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取证书的剩余有效天数
     * 
     * @param cert X.509 证书
     * @return 剩余天数，如果已过期返回负数
     */
    public static long getRemainingValidityDays(X509Certificate cert) {
        if (cert == null) {
            return -1;
        }
        Date notAfter = cert.getNotAfter();
        long currentTime = System.currentTimeMillis();
        long expiryTime = notAfter.getTime();
        long diffMillis = expiryTime - currentTime;
        return diffMillis / (24L * 60L * 60L * 1000L);
    }

    /**
     * 将字节数组转换为十六进制字符串
     * 
     * @param bytes 字节数组
     * @return 十六进制字符串（用冒号分隔）
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("%02X", bytes[i]));
            if (i < bytes.length - 1) {
                sb.append(":");
            }
        }
        return sb.toString();
    }

    /**
     * 比较两个证书是否相同（通过指纹比较）
     * 
     * @param cert1 第一个证书
     * @param cert2 第二个证书
     * @return true 如果证书相同
     */
    public static boolean areCertificatesEqual(X509Certificate cert1, X509Certificate cert2) {
        if (cert1 == null || cert2 == null) {
            return false;
        }
        String fp1 = getCertificateFingerprint(cert1);
        String fp2 = getCertificateFingerprint(cert2);
        return fp1 != null && fp1.equals(fp2);
    }
}

