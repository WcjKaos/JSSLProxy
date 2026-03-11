package com.sslproxy.util;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TlsUtil 单元测试
 */
class TlsUtilTest {

    @Test
    void testEnableHostnameVerification_SetsAlgorithm() throws Exception {
        SSLContext sslContext = SSLContext.getDefault();
        SSLEngine engine = sslContext.createSSLEngine("example.com", 443);

        // Before: no endpoint identification
        assertNull(engine.getSSLParameters().getEndpointIdentificationAlgorithm());

        TlsUtil.enableHostnameVerification(engine);

        // After: HTTPS algorithm set
        assertEquals("HTTPS", engine.getSSLParameters().getEndpointIdentificationAlgorithm());
    }

    @Test
    void testEnableHostnameVerification_NullEngine_NoException() {
        assertDoesNotThrow(() -> TlsUtil.enableHostnameVerification(null));
    }

    @Test
    void testEnableHostnameVerification_Idempotent() throws Exception {
        SSLContext sslContext = SSLContext.getDefault();
        SSLEngine engine = sslContext.createSSLEngine("example.com", 443);

        TlsUtil.enableHostnameVerification(engine);
        TlsUtil.enableHostnameVerification(engine);

        assertEquals("HTTPS", engine.getSSLParameters().getEndpointIdentificationAlgorithm());
    }
}
