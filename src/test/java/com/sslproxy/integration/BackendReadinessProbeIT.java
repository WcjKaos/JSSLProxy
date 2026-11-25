package com.sslproxy.integration;

import com.sslproxy.config.ProxyConfig;
import com.sslproxy.loadbalance.LoadBalancerManager;
import com.sslproxy.server.TransportFactory;
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BackendReadinessProbeIT {
    @Test
    void should_checkHttpHealthAndDetectFailure_when_periodicChecksAreEnabled() throws Exception {
        EventLoopGroup eventLoops = TransportFactory.createEventLoopGroup(1);
        try (TestBackendServer backend = new TestBackendServer(IntegrationTestBase.findAvailablePort())) {
            backend.start();
            ProxyConfig.Target target = new ProxyConfig.Target();
            target.host = "localhost";
            target.port = backend.getPort();
            target.protocol = "http";
            target.healthCheck = new ProxyConfig.HealthCheckConfig();
            target.healthCheck.enabled = true;
            target.healthCheck.intervalSeconds = 1;
            target.healthCheck.timeoutMs = 500;
            target.healthCheck.unhealthyThreshold = 1;
            target.healthCheck.healthyThreshold = 1;
            LoadBalancerManager manager = new LoadBalancerManager(target, eventLoops);
            try {
                manager.startHealthCheck();
                await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertThat(backend.getRequestCount()).isPositive();
                    assertThat(manager.checkReadiness().getNow(Map.of()))
                            .containsEntry("localhost:" + backend.getPort(), true);
                });
                backend.withFailure(true);
                await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
                    assertThat(manager.checkReadiness().getNow(Map.of()))
                            .containsEntry("localhost:" + backend.getPort(), false);
                    assertThat(manager.getLoadBalancer().getBackends().get(0).isHealthy()).isFalse();
                });
            } finally {
                manager.stop();
            }
        } finally {
            eventLoops.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    void should_closeResolverAndFailReadiness_when_managerStops() throws Exception {
        Set<Thread> previousThreads = Thread.getAllStackTraces().keySet();
        EventLoopGroup eventLoops = TransportFactory.createEventLoopGroup(1);
        try (ServerSocket backend = new ServerSocket(0)) {
            ProxyConfig.Target target = new ProxyConfig.Target();
            target.host = "localhost";
            target.port = backend.getLocalPort();
            target.protocol = "http";
            LoadBalancerManager manager = new LoadBalancerManager(target, eventLoops);
            try {
                Map<String, Boolean> initial = manager.checkReadiness().get(5, TimeUnit.SECONDS);
                assertThat(initial).containsEntry("localhost:" + target.port, true);
                Set<Thread> resolverThreads = Thread.getAllStackTraces().keySet();
                resolverThreads.removeAll(previousThreads);
                resolverThreads.removeIf(thread -> !thread.getName().equals("backend-health-resolver"));
                assertThat(resolverThreads).hasSize(1);

                manager.stop();
                manager.stop();

                assertThat(manager.checkReadiness().get(1, TimeUnit.SECONDS))
                        .containsEntry("localhost:" + target.port, false);
                await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                        assertThat(resolverThreads).noneMatch(Thread::isAlive));
                assertThat(manager.getLoadBalancer().getBackends().get(0).isHealthy()).isTrue();
            } finally {
                manager.stop();
            }
        } finally {
            eventLoops.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
