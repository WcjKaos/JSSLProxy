package com.sslproxy.handler;

import io.netty.handler.ssl.SslHandshakeTimeoutException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateRevokedException;
import java.security.cert.CRLReason;

import javax.security.auth.x500.X500Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SSLAuthHandler.classifyHandshakeFailure 分类逻辑单元测试（T-03）
 */
class SSLHandshakeFailureClassificationTest {

    @Test
    void should_returnTimeout_when_SslHandshakeTimeoutException() {
        assertEquals("timeout", SSLAuthHandler.classifyHandshakeFailure(new SslHandshakeTimeoutException("handshake timed out")));
    }

    @Test
    void should_returnExpired_when_CertificateExpiredException_inChain() {
        Exception root = new CertificateExpiredException("NotAfter: ...");
        Exception wrapper = new javax.net.ssl.SSLHandshakeException("cert expired");
        wrapper.initCause(root);

        assertEquals("expired", SSLAuthHandler.classifyHandshakeFailure(wrapper));
    }

    @Test
    void should_returnExpired_when_messageContainsExpired() {
        Exception e = new javax.net.ssl.SSLHandshakeException("certificate expired");
        assertEquals("expired", SSLAuthHandler.classifyHandshakeFailure(e));
    }

    @Test
    void should_returnRevoked_when_CertificateRevokedException_inChain() {
        Exception root = new CertificateRevokedException(
                new java.util.Date(), CRLReason.KEY_COMPROMISE,
                new X500Principal("CN=TestCA"), new java.util.HashMap<>());
        Exception wrapper = new javax.net.ssl.SSLHandshakeException("cert revoked");
        wrapper.initCause(root);

        assertEquals("revoked", SSLAuthHandler.classifyHandshakeFailure(wrapper));
    }

    @Test
    void should_returnRevoked_when_messageContainsRevoked() {
        Exception e = new javax.net.ssl.SSLHandshakeException("certificate revoked");
        assertEquals("revoked", SSLAuthHandler.classifyHandshakeFailure(e));
    }

    @Test
    void should_returnUntrusted_when_messageContainsPkixPath() {
        Exception e = new javax.net.ssl.SSLHandshakeException(
                "PKIX path building failed: unable to find valid certification path to requested target");
        assertEquals("untrusted", SSLAuthHandler.classifyHandshakeFailure(e));
    }

    @Test
    void should_returnUntrusted_when_messageContainsKeyTooWeak() {
        Exception e = new javax.net.ssl.SSLHandshakeException(
                "Client certificate RSA key is too weak: 1024 bits");
        assertEquals("untrusted", SSLAuthHandler.classifyHandshakeFailure(e));
    }

    @Test
    void should_returnCipherMismatch_when_messageContainsNoCipher() {
        Exception e = new javax.net.ssl.SSLHandshakeException("no cipher suites in common");
        assertEquals("cipher_mismatch", SSLAuthHandler.classifyHandshakeFailure(e));
    }

    @Test
    void should_returnProtocolMismatch_when_messageContainsNoAppropriateProtocol() {
        Exception e = new javax.net.ssl.SSLHandshakeException("No appropriate protocol (protocol is disabled or cipher suites are inappropriate)");
        assertEquals("protocol_mismatch", SSLAuthHandler.classifyHandshakeFailure(e));
    }

    @Test
    void should_returnNoCert_when_messageContainsEmptyClientCertificate() {
        Exception e = new javax.net.ssl.SSLHandshakeException("Empty client certificate chain");
        assertEquals("no_cert", SSLAuthHandler.classifyHandshakeFailure(e));
    }

    @Test
    void should_returnOther_when_causeIsNull() {
        assertEquals("other", SSLAuthHandler.classifyHandshakeFailure(null));
    }

    @Test
    void should_returnOther_when_unknownException() {
        assertEquals("other", SSLAuthHandler.classifyHandshakeFailure(new ConnectException("something else")));
    }

    @Test
    void should_returnOther_when_messageIsNull() {
        assertEquals("other", SSLAuthHandler.classifyHandshakeFailure(new RuntimeException((String) null)));
    }
}
