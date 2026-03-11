package com.sslproxy.management.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.sslproxy.config.ProxyConfig;

/**
 * AdminApiHandler 指纹格式验证测试
 */
class AdminApiConnectionTerminateTest {

    @Test
    void should_acceptValid_when_colonSeparatedSha256() throws Exception {
        assertThat(isValid("AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"))
                .isTrue();
    }

    @Test
    void should_acceptValid_when_plainHex64() throws Exception {
        assertThat(isValid("AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899"))
                .isTrue();
    }

    @Test
    void should_acceptValid_when_lowercaseHex() throws Exception {
        assertThat(isValid("aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"))
                .isTrue();
    }

    @Test
    void should_rejectInvalid_when_tooShort() throws Exception {
        assertThat(isValid("AABB")).isFalse();
    }

    @Test
    void should_rejectInvalid_when_tooLong() throws Exception {
        assertThat(isValid("AABBCCDDEEFF00112233445566778899AABBCCDDEEFF001122334455667788990011"))
                .isFalse();
    }

    @Test
    void should_rejectInvalid_when_nonHexChars() throws Exception {
        assertThat(isValid("GGHHIIJJKKLL00112233445566778899AABBCCDDEEFF00112233445566778899"))
                .isFalse();
    }

    @Test
    void should_rejectInvalid_when_null() throws Exception {
        assertThat(isValid(null)).isFalse();
    }

    @Test
    void should_rejectInvalid_when_empty() throws Exception {
        assertThat(isValid("")).isFalse();
    }

    /**
     * Call the private isValidFingerprint method via reflection
     */
    private boolean isValid(String fingerprint) throws Exception {
        AdminApiHandler handler = new AdminApiHandler(new ProxyConfig(), null,
                null, null, null, null, new com.sslproxy.audit.AuditLogger(),
                com.sslproxy.metrics.ProxyMetrics.noop());
        Method m = AdminApiHandler.class.getDeclaredMethod("isValidFingerprint", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(handler, fingerprint);
    }
}
