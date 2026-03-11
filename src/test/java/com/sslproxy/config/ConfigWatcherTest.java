package com.sslproxy.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ConfigWatcher 单元测试
 */
class ConfigWatcherTest {

    @TempDir
    Path tempDir;

    private Path configFile;
    private ConfigWatcher watcher;

    @BeforeEach
    void setUp() throws IOException {
        // Create dummy keystore/truststore files so ProxyConfig.validate() passes
        Files.write(tempDir.resolve("dummy.p12"), new byte[0]);
        Files.write(tempDir.resolve("dummy-trust.p12"), new byte[0]);

        configFile = tempDir.resolve("application.yml");
        Files.writeString(configFile, createMinimalConfig(false, 5000));
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void should_initializeWithConfig_when_constructed() throws IOException {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        assertThat(watcher.getCurrentConfig()).isSameAs(config);
    }

    @Test
    void should_addAndRemoveListener_when_called() throws IOException {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        AtomicInteger callCount = new AtomicInteger(0);
        java.util.function.Consumer<ProxyConfig> listener = c -> callCount.incrementAndGet();

        watcher.addListener(listener);
        watcher.triggerReload();
        assertThat(callCount.get()).isEqualTo(1);

        watcher.removeListener(listener);
        watcher.triggerReload();
        assertThat(callCount.get()).isEqualTo(1); // listener removed, no increment
    }

    @Test
    void should_mergeRateLimitConfig_when_reloaded() throws IOException {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        // Write updated config to file
        Files.writeString(configFile, createMinimalConfig(true, 10000));

        watcher.triggerReload();

        assertThat(config.rateLimit.enabled).isTrue();
        assertThat(config.rateLimit.globalRps).isEqualTo(10000);
    }

    @Test
    void should_notifyListeners_when_configReloaded() throws IOException {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        AtomicReference<ProxyConfig> received = new AtomicReference<>();
        watcher.addListener(received::set);

        Files.writeString(configFile, createMinimalConfig(true, 8000));
        watcher.triggerReload();

        assertThat(received.get()).isSameAs(config);
        assertThat(config.rateLimit.enabled).isTrue();
    }

    @Test
    void should_handleListenerException_when_listenerThrows() throws IOException {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        AtomicInteger goodListenerCalls = new AtomicInteger(0);

        // Bad listener that throws
        watcher.addListener(c -> { throw new RuntimeException("test error"); });
        // Good listener after the bad one
        watcher.addListener(c -> goodListenerCalls.incrementAndGet());

        watcher.triggerReload();

        // Good listener should still be called even after bad listener throws
        assertThat(goodListenerCalls.get()).isEqualTo(1);
    }

    @Test
    void should_notCrash_when_configFileInvalid() throws IOException {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        // Write invalid YAML
        Files.writeString(configFile, "{{invalid yaml content}}");

        // Should not throw
        watcher.triggerReload();

        // Original config should be preserved
        assertThat(config.rateLimit.enabled).isFalse();
    }

    @Test
    void should_startAndStop_when_called() throws IOException {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        // Should not throw
        watcher.start();
        watcher.stop();
    }

    @Test
    void should_detectFileChange_when_watching() throws Exception {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        CountDownLatch latch = new CountDownLatch(1);
        watcher.addListener(c -> latch.countDown());
        watcher.start();

        // Give the watcher time to register
        Thread.sleep(200);

        // Modify the config file
        Files.writeString(configFile, createMinimalConfig(true, 9999));

        // Wait for the file change to be detected (200ms debounce + poll interval)
        boolean detected = latch.await(5, TimeUnit.SECONDS);

        assertThat(detected).isTrue();
        assertThat(config.rateLimit.enabled).isTrue();
        assertThat(config.rateLimit.globalRps).isEqualTo(9999);
    }

    @Test
    void should_ignoreDoubleStart_when_alreadyRunning() throws IOException {
        ProxyConfig config = createConfig(false, 5000);
        watcher = new ConfigWatcher(configFile.toString(), config);

        watcher.start();
        watcher.start(); // second call should be no-op, not throw

        watcher.stop();
    }

    private ProxyConfig createConfig(boolean rateLimitEnabled, int globalRps) {
        ProxyConfig config = new ProxyConfig();
        config.server = new ProxyConfig.Server();
        config.server.host = "0.0.0.0";
        config.server.port = 18443;

        config.target = new ProxyConfig.Target();
        config.target.host = "localhost";
        config.target.port = 8080;
        config.target.protocol = "http";

        config.ssl = new ProxyConfig.Ssl();
        config.ssl.keystore = new ProxyConfig.KeystoreConfig();
        config.ssl.keystore.path = "dummy.p12";
        config.ssl.keystore.password = "changeit";
        config.ssl.keystore.type = "PKCS12";
        config.ssl.clientAuth = "NONE";
        config.ssl.protocols = Arrays.asList("TLSv1.3", "TLSv1.2");

        config.rateLimit = new ProxyConfig.RateLimit();
        config.rateLimit.enabled = rateLimitEnabled;
        config.rateLimit.globalRps = globalRps;
        config.rateLimit.globalBurstCapacity = 500;
        config.rateLimit.clientRps = 200;
        config.rateLimit.clientBurstCapacity = 50;

        return config;
    }

    private String createMinimalConfig(boolean rateLimitEnabled, int globalRps) {
        // Use forward slashes and escape backslashes for YAML compatibility
        String keystorePath = tempDir.resolve("dummy.p12").toString().replace("\\", "/");
        String truststorePath = tempDir.resolve("dummy-trust.p12").toString().replace("\\", "/");
        return "server:\n" +
               "  host: 0.0.0.0\n" +
               "  port: 18443\n" +
               "target:\n" +
               "  host: localhost\n" +
               "  port: 8080\n" +
               "  protocol: http\n" +
               "ssl:\n" +
               "  keystore:\n" +
               "    path: " + keystorePath + "\n" +
               "    password: changeit\n" +
               "    type: PKCS12\n" +
               "  truststore:\n" +
               "    path: " + truststorePath + "\n" +
               "    password: changeit\n" +
               "    type: PKCS12\n" +
               "  clientAuth: NONE\n" +
               "  protocols: [TLSv1.3, TLSv1.2]\n" +
               "rateLimit:\n" +
               "  enabled: " + rateLimitEnabled + "\n" +
               "  globalRps: " + globalRps + "\n" +
               "  globalBurstCapacity: 500\n" +
               "  clientRps: 200\n" +
               "  clientBurstCapacity: 50\n";
    }
}
