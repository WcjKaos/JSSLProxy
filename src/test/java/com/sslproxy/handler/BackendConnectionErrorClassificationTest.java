package com.sslproxy.handler;

import io.netty.channel.ConnectTimeoutException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ProxyFrontendHandler.classifyConnectionError 分类逻辑单元测试（T-04）
 */
class BackendConnectionErrorClassificationTest {

    @Test
    void should_returnTimeout_when_ConnectTimeoutException() {
        assertEquals("timeout", ProxyFrontendHandler.classifyConnectionError(new ConnectTimeoutException("timed out")));
    }

    @Test
    void should_returnRefused_when_ConnectExceptionWithRefused() {
        assertEquals("refused", ProxyFrontendHandler.classifyConnectionError(new ConnectException("Connection refused: backend:8080")));
    }

    @Test
    void should_returnTls_when_SSLException() {
        assertEquals("tls", ProxyFrontendHandler.classifyConnectionError(new SSLException("Handshake failure")));
    }

    @Test
    void should_returnDns_when_UnknownHostException() {
        assertEquals("dns", ProxyFrontendHandler.classifyConnectionError(new UnknownHostException("backend.unknown")));
    }

    @Test
    void should_returnPoolExhausted_when_messageContainsPoolExhausted() {
        assertEquals("pool_exhausted", ProxyFrontendHandler.classifyConnectionError(
                new RuntimeException("Connection pool exhausted after 5000ms")));
    }

    @Test
    void should_returnPoolExhausted_when_messageContainsPoolAcquireTimeout() {
        assertEquals("pool_exhausted", ProxyFrontendHandler.classifyConnectionError(
                new RuntimeException("pool acquire timeout")));
    }

    @Test
    void should_returnOther_when_causeIsNull() {
        assertEquals("other", ProxyFrontendHandler.classifyConnectionError(null));
    }

    @Test
    void should_returnOther_when_unknownException() {
        assertEquals("other", ProxyFrontendHandler.classifyConnectionError(new IllegalStateException("something else")));
    }

    @Test
    void should_returnOther_when_ConnectExceptionWithoutRefused() {
        assertEquals("other", ProxyFrontendHandler.classifyConnectionError(new ConnectException("Network unreachable")));
    }

    @Test
    void should_returnTimeout_when_messageContainsConnectTimeout() {
        assertEquals("timeout", ProxyFrontendHandler.classifyConnectionError(
                new RuntimeException("connect timeout after 30s")));
    }
}
