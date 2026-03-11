package com.sslproxy.loadbalance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sslproxy.loadbalance.LoadBalancer.Backend;
import com.sslproxy.loadbalance.LoadBalancer.Strategy;

/**
 * LoadBalancer 单元测试
 */
class LoadBalancerTest {

    private LoadBalancer loadBalancer;

    @BeforeEach
    void setUp() {
        loadBalancer = new LoadBalancer(Strategy.ROUND_ROBIN);
    }

    // ========== 构造函数测试 ==========

    @Test
    void testConstructor_DefaultStrategy() {
        LoadBalancer lb = new LoadBalancer();
        assertEquals(Strategy.ROUND_ROBIN, lb.getStrategy());
    }

    @Test
    void testConstructor_WithStrategy() {
        LoadBalancer lb = new LoadBalancer(Strategy.LEAST_CONNECTIONS);
        assertEquals(Strategy.LEAST_CONNECTIONS, lb.getStrategy());
    }

    // ========== Backend 类测试 ==========

    @Test
    void testBackend_Constructor() {
        Backend backend = new Backend("localhost", 8080, "http", 5);

        assertEquals("localhost", backend.getHost());
        assertEquals(8080, backend.getPort());
        assertEquals("http", backend.getProtocol());
        assertEquals(5, backend.getWeight());
        assertTrue(backend.isHealthy());
        assertEquals(0, backend.getActiveConnections());
    }

    @Test
    void testBackend_DefaultWeight() {
        Backend backend = new Backend("localhost", 8080, "http");
        assertEquals(1, backend.getWeight());
    }

    @Test
    void testBackend_NegativeWeight_BecomesOne() {
        Backend backend = new Backend("localhost", 8080, "http", -5);
        assertEquals(1, backend.getWeight());
    }

    @Test
    void testBackend_ConnectionTracking() {
        Backend backend = new Backend("localhost", 8080, "http");

        assertEquals(0, backend.getActiveConnections());

        backend.incrementConnections();
        backend.incrementConnections();
        assertEquals(2, backend.getActiveConnections());
        assertEquals(2, backend.getTotalRequests());

        backend.decrementConnections();
        assertEquals(1, backend.getActiveConnections());
        assertEquals(2, backend.getTotalRequests()); // Total 不变
    }

    @Test
    void testBackend_HealthStatus() {
        Backend backend = new Backend("localhost", 8080, "http");
        assertTrue(backend.isHealthy());

        backend.setHealthy(false);
        assertFalse(backend.isHealthy());

        backend.setHealthy(true);
        assertTrue(backend.isHealthy());
    }

    @Test
    void testBackend_GetKey() {
        Backend backend = new Backend("example.com", 443, "https");
        assertEquals("example.com:443", backend.getKey());
    }

    // ========== addBackend / removeBackend 测试 ==========

    @Test
    void testAddBackend() {
        assertEquals(0, loadBalancer.getBackends().size());

        loadBalancer.addBackend(new Backend("host1", 8080, "http"));
        assertEquals(1, loadBalancer.getBackends().size());

        loadBalancer.addBackend(new Backend("host2", 8081, "http"));
        assertEquals(2, loadBalancer.getBackends().size());
    }

    @Test
    void testRemoveBackend() {
        loadBalancer.addBackend(new Backend("host1", 8080, "http"));
        loadBalancer.addBackend(new Backend("host2", 8081, "http"));
        assertEquals(2, loadBalancer.getBackends().size());

        loadBalancer.removeBackend("host1", 8080);
        assertEquals(1, loadBalancer.getBackends().size());
        assertEquals("host2", loadBalancer.getBackends().get(0).getHost());
    }

    // ========== selectBackend - Round Robin 测试 ==========

    @Test
    void testSelectBackend_RoundRobin_CyclesThroughBackends() {
        loadBalancer.addBackend(new Backend("host1", 8080, "http"));
        loadBalancer.addBackend(new Backend("host2", 8081, "http"));
        loadBalancer.addBackend(new Backend("host3", 8082, "http"));

        Backend b1 = loadBalancer.selectBackend();
        Backend b2 = loadBalancer.selectBackend();
        Backend b3 = loadBalancer.selectBackend();
        Backend b4 = loadBalancer.selectBackend();

        assertNotNull(b1);
        assertNotNull(b2);
        assertNotNull(b3);
        assertNotNull(b4);

        // 验证轮询
        assertEquals("host1", b1.getHost());
        assertEquals("host2", b2.getHost());
        assertEquals("host3", b3.getHost());
        assertEquals("host1", b4.getHost()); // 回到第一个
    }

    // ========== selectBackend - Least Connections 测试 ==========

    @Test
    void testSelectBackend_LeastConnections() {
        LoadBalancer lb = new LoadBalancer(Strategy.LEAST_CONNECTIONS);

        Backend b1 = new Backend("host1", 8080, "http");
        Backend b2 = new Backend("host2", 8081, "http");
        Backend b3 = new Backend("host3", 8082, "http");

        lb.addBackend(b1);
        lb.addBackend(b2);
        lb.addBackend(b3);

        // 给 b1 和 b2 增加连接
        b1.incrementConnections();
        b1.incrementConnections();
        b2.incrementConnections();

        // 应该选择 b3（0 连接）
        Backend selected = lb.selectBackend();
        assertEquals("host3", selected.getHost());
    }

    @Test
    void testSelectBackend_LeastConnections_ConsidersWeight() {
        LoadBalancer lb = new LoadBalancer(Strategy.LEAST_CONNECTIONS);

        Backend b1 = new Backend("host1", 8080, "http", 2); // 权重 2
        Backend b2 = new Backend("host2", 8081, "http", 1); // 权重 1

        lb.addBackend(b1);
        lb.addBackend(b2);

        // b1: 2 连接，权重 2 → 得分 1.0
        // b2: 1 连接，权重 1 → 得分 1.0
        b1.incrementConnections();
        b1.incrementConnections();
        b2.incrementConnections();

        // 两者得分相同，选择第一个（或任意一个）
        Backend selected = lb.selectBackend();
        assertNotNull(selected);
    }

    // ========== selectBackend - Random 测试 ==========

    @Test
    void testSelectBackend_Random_SelectsAllBackends() {
        LoadBalancer lb = new LoadBalancer(Strategy.RANDOM);

        lb.addBackend(new Backend("host1", 8080, "http"));
        lb.addBackend(new Backend("host2", 8081, "http"));
        lb.addBackend(new Backend("host3", 8082, "http"));

        Set<String> selectedHosts = new HashSet<>();

        // 多次选择，应该能覆盖所有后端
        for (int i = 0; i < 100; i++) {
            Backend selected = lb.selectBackend();
            selectedHosts.add(selected.getHost());
        }

        // 应该选择过所有 3 个后端
        assertEquals(3, selectedHosts.size());
    }

    // ========== selectBackend - Weighted Round Robin 测试 ==========

    @Test
    void testSelectBackend_WeightedRoundRobin_RespectsWeights() {
        LoadBalancer lb = new LoadBalancer(Strategy.WEIGHTED_ROUND_ROBIN);

        Backend b1 = new Backend("host1", 8080, "http", 3); // 权重 3
        Backend b2 = new Backend("host2", 8081, "http", 1); // 权重 1

        lb.addBackend(b1);
        lb.addBackend(b2);

        int host1Count = 0;
        int host2Count = 0;

        // 多次选择，统计分布
        for (int i = 0; i < 1000; i++) {
            Backend selected = lb.selectBackend();
            if ("host1".equals(selected.getHost())) {
                host1Count++;
            } else {
                host2Count++;
            }
        }

        // host1 应该被选中大约 3 倍于 host2
        double ratio = (double) host1Count / host2Count;
        assertTrue(ratio > 2.0 && ratio < 4.0, "Ratio should be around 3, got: " + ratio);
    }

    // ========== selectBackend - 健康检查 ==========

    @Test
    void testSelectBackend_SkipsUnhealthyBackends() {
        loadBalancer.addBackend(new Backend("host1", 8080, "http"));
        loadBalancer.addBackend(new Backend("host2", 8081, "http"));

        // 标记 host1 为不健康
        loadBalancer.markUnhealthy("host1", 8080);

        // 应该只选择 host2
        for (int i = 0; i < 10; i++) {
            Backend selected = loadBalancer.selectBackend();
            assertEquals("host2", selected.getHost());
        }
    }

    @Test
    void testSelectBackend_NoHealthyBackends_ReturnsNull() {
        loadBalancer.addBackend(new Backend("host1", 8080, "http"));
        loadBalancer.markUnhealthy("host1", 8080);

        Backend selected = loadBalancer.selectBackend();
        assertNull(selected);
    }

    @Test
    void testSelectBackend_EmptyBackends_ReturnsNull() {
        Backend selected = loadBalancer.selectBackend();
        assertNull(selected);
    }

    // ========== markHealthy / markUnhealthy 测试 ==========

    @Test
    void testMarkUnhealthy() {
        loadBalancer.addBackend(new Backend("host1", 8080, "http"));
        assertEquals(1, loadBalancer.getHealthyBackendCount());

        loadBalancer.markUnhealthy("host1", 8080);
        assertEquals(0, loadBalancer.getHealthyBackendCount());
    }

    @Test
    void testMarkHealthy() {
        loadBalancer.addBackend(new Backend("host1", 8080, "http"));
        loadBalancer.markUnhealthy("host1", 8080);
        assertEquals(0, loadBalancer.getHealthyBackendCount());

        loadBalancer.markHealthy("host1", 8080);
        assertEquals(1, loadBalancer.getHealthyBackendCount());
    }

    // ========== getHealthyBackendCount 测试 ==========

    @Test
    void testGetHealthyBackendCount() {
        loadBalancer.addBackend(new Backend("host1", 8080, "http"));
        loadBalancer.addBackend(new Backend("host2", 8081, "http"));
        loadBalancer.addBackend(new Backend("host3", 8082, "http"));

        assertEquals(3, loadBalancer.getHealthyBackendCount());

        loadBalancer.markUnhealthy("host2", 8081);
        assertEquals(2, loadBalancer.getHealthyBackendCount());
    }

    // ========== getBackends 测试 ==========

    @Test
    void testGetBackends_ReturnsCopy() {
        loadBalancer.addBackend(new Backend("host1", 8080, "http"));

        // 获取副本
        var backends = loadBalancer.getBackends();

        // 尝试修改副本应该不影响原列表
        assertThrows(UnsupportedOperationException.class, () -> {
            backends.add(new Backend("host2", 8081, "http"));
        });
    }
}
