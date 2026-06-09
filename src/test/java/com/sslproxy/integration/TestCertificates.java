package com.sslproxy.integration;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.*;

/**
 * 集成测试用证书生成工具
 *
 * 使用 keytool 命令行工具生成自签名证书用于测试 SSL/TLS 连接
 */
public class TestCertificates {

    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final char[] DEFAULT_PASSWORD = "testpass".toCharArray();

    private final Path tempDir;

    public TestCertificates() throws IOException {
        this.tempDir = Files.createTempDirectory("ssl-proxy-test-certs");
    }

    /**
     * 初始化 CA 证书（使用 keytool 生成自签名证书）
     */
    public void initializeCA() throws Exception {
        // 使用 keytool 生成自签名 CA 证书
        // 这里我们不需要真正的 CA，只需要自签名证书用于测试
    }

    /**
     * 生成服务器证书和密钥库
     */
    public Path generateServerKeyStore(String hostname) throws Exception {
        Path keystorePath = tempDir.resolve("server-" + hostname + ".p12");

        // 使用 keytool 生成自签名证书
        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-dname", "CN=" + hostname + ", O=JSSLProxy Test, C=CN",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", new String(DEFAULT_PASSWORD),
                "-keypass", new String(DEFAULT_PASSWORD)
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 读取输出
        try (InputStream is = process.getInputStream()) {
            is.transferTo(System.out);
        }

        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed || process.exitValue() != 0) {
            throw new RuntimeException("Failed to generate server keystore, exit code: " + process.exitValue());
        }

        return keystorePath;
    }

    /**
     * 生成客户端证书和密钥库
     */
    public Path generateClientKeyStore(String clientName) throws Exception {
        Path keystorePath = tempDir.resolve("client-" + clientName + ".p12");

        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "client",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-dname", "CN=" + clientName + ", O=JSSLProxy Test Client, C=CN",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", new String(DEFAULT_PASSWORD),
                "-keypass", new String(DEFAULT_PASSWORD)
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (InputStream is = process.getInputStream()) {
            is.transferTo(System.out);
        }

        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed || process.exitValue() != 0) {
            throw new RuntimeException("Failed to generate client keystore, exit code: " + process.exitValue());
        }

        return keystorePath;
    }

    /**
     * 生成信任库（导出服务器证书并导入到信任库）
     */
    public Path generateTrustStore() throws Exception {
        Path truststorePath = tempDir.resolve("truststore.p12");
        Path serverKeystorePath = tempDir.resolve("server-localhost.p12");
        Path clientKeystorePath = tempDir.resolve("client-test-client.p12");

        exportAndImportCertificate(serverKeystorePath, "server", truststorePath, "server");
        exportAndImportCertificate(clientKeystorePath, "client", truststorePath, "client");

        return truststorePath;
    }

    private void exportAndImportCertificate(Path sourceKeystorePath, String sourceAlias,
                                            Path truststorePath, String trustAlias) throws Exception {
        Path certPath = tempDir.resolve(trustAlias + ".cer");

        ProcessBuilder exportPb = new ProcessBuilder(
                "keytool", "-exportcert",
                "-alias", sourceAlias,
                "-keystore", sourceKeystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", new String(DEFAULT_PASSWORD),
                "-file", certPath.toString(),
                "-rfc"
        );
        exportPb.redirectErrorStream(true);
        Process exportProcess = exportPb.start();
        try (InputStream is = exportProcess.getInputStream()) {
            is.transferTo(System.out);
        }
        boolean exportCompleted = exportProcess.waitFor(30, TimeUnit.SECONDS);
        if (!exportCompleted || exportProcess.exitValue() != 0) {
            throw new RuntimeException("Failed to export " + trustAlias + " certificate");
        }

        ProcessBuilder importPb = new ProcessBuilder(
                "keytool", "-importcert",
                "-alias", trustAlias,
                "-keystore", truststorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", new String(DEFAULT_PASSWORD),
                "-file", certPath.toString(),
                "-noprompt"
        );
        importPb.redirectErrorStream(true);
        Process importProcess = importPb.start();
        try (InputStream is = importProcess.getInputStream()) {
            is.transferTo(System.out);
        }
        boolean importCompleted = importProcess.waitFor(30, TimeUnit.SECONDS);
        if (!importCompleted || importProcess.exitValue() != 0) {
            throw new RuntimeException("Failed to import " + trustAlias + " certificate to truststore");
        }
    }

    /**
     * 获取默认密码
     */
    public static char[] getDefaultPassword() {
        return DEFAULT_PASSWORD;
    }

    /**
     * 获取默认密码字符串
     */
    public static String getDefaultPasswordString() {
        return new String(DEFAULT_PASSWORD);
    }

    /**
     * 获取临时目录
     */
    public Path getTempDir() {
        return tempDir;
    }

    /**
     * 创建服务端 SSLContext
     */
    public SSLContext createServerSSLContext(Path keystorePath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(Files.newInputStream(keystorePath), DEFAULT_PASSWORD);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, DEFAULT_PASSWORD);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return sslContext;
    }

    /**
     * 创建客户端 SSLContext（信任所有证书，用于测试）
     */
    public SSLContext createTrustAllSSLContext() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return sslContext;
    }

    /**
     * 创建客户端 SSLContext（带客户端证书）
     */
    public SSLContext createClientSSLContext(Path clientKeystorePath) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(Files.newInputStream(clientKeystorePath), DEFAULT_PASSWORD);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, DEFAULT_PASSWORD);

        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), trustAllCerts, new SecureRandom());
        return sslContext;
    }

    /**
     * 清理临时文件
     */
    public void cleanup() {
        try {
            Files.walk(tempDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        } catch (IOException e) {
            // ignore
        }
    }
}
