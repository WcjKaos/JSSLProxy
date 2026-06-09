# Testing Strategy — JSSLProxy

## 核心原则

- NEVER 弱化断言让测试通过 — 修改代码使测试通过
- 测试必须覆盖「快乐路径 + 边界条件 + 错误路径」
- 异步测试 MUST 使用 Awaitility，NEVER 使用 `Thread.sleep()`
- 每个测试 MUST 相互独立，不依赖外部状态

---

## 测试分层

### 单元测试（`src/test/java/com/sslproxy/`）
- 工具：JUnit 5 + AssertJ + Mockito
- 适用：纯逻辑类（`ProxyConfig`、`RateLimiterManager`、`ClientAuthenticator` 等）
- NEVER mock Netty 底层类（`Channel`、`ChannelHandlerContext`）— 用集成测试代替
- 证书相关测试用 `TestCertificates` 工具生成内存证书

```java
// 命名规范：should_<action>_when_<condition>
@Test
void should_rejectRequest_when_fingerprintInBlacklist() { ... }

// AAA 模式
@Test
void should_allowRequest_when_validCertificate() {
    // Arrange
    var cert = TestCertificates.generateClientCert();

    // Act
    var result = authenticator.authenticate(cert);

    // Assert
    assertThat(result).isTrue();
}
```

### 集成测试（类名以 `IT` 结尾）
- 基类：`IntegrationTestBase`（启动真实代理服务 + `TestBackendServer`）
- 工具：Apache HttpClient 5（支持 mTLS）+ Awaitility
- 使用真实 TLS 连接，不用 mock
- 测试必须使用 `TestCertificates` 生成测试证书

```java
// 集成测试框架用法
@Test
void should_forwardRequest_when_validMtlsClient() throws Exception {
    // 使用 IntegrationTestBase 提供的 httpsClient（已配置 mTLS）
    HttpResponse response = httpsClient.execute(new HttpGet(proxyUrl + "/api/test"));
    assertThat(response.getCode()).isEqualTo(200);
}

// 异步断言
await().atMost(5, SECONDS).untilAsserted(() ->
    assertThat(metrics.getCounter("proxy.requests.total").count()).isEqualTo(1)
);
```

### 性能测试（JMH）
- 位置：`src/jmh/java/`
- 命令：`./gradlew jmh`
- 提交影响吞吐量的变更前 MUST 运行 JMH，对比前后结果
- 结果保存在 `build/reports/jmh/results.json`

---

## 必须覆盖的测试场景

### TLS / mTLS
- [ ] 有效客户端证书 → 200 通过
- [ ] 证书不在白名单 → 403 拒绝
- [ ] 证书在黑名单 → 403 拒绝
- [ ] 证书已吊销（OCSP/CRL mock）→ 403 拒绝
- [ ] 无客户端证书（REQUIRED 模式）→ TLS 握手失败
- [ ] 证书已过期 → 拒绝
- [ ] 自签名证书（NONE 吊销策略）→ 通过

### 限流
- [ ] 请求在限额内 → 通过
- [ ] 超过全局限额 → 429
- [ ] 超过客户端限额 → 429
- [ ] 热加载新限额后生效

### 熔断器
- [ ] 后端连续失败触发熔断 → 503
- [ ] 熔断打开期间新请求 → 503（不到达后端）
- [ ] 后端恢复后熔断关闭 → 正常代理

### 负载均衡
- [ ] 多后端轮询分发
- [ ] 不健康后端不接受流量
- [ ] 所有后端不健康 → 503

### 边界条件
- [ ] 超过最大连接数 → 拒绝新连接
- [ ] 请求体超过 `maxRequestSize` → 413
- [ ] 空白 fingerprint / 格式错误 fingerprint → 校验失败
- [ ] 后端断开 → 前端收到 502，连接正确关闭
- [ ] 空闲超时 → 连接正确关闭

---

## Netty 测试注意事项

- Handler 的 `channelWritabilityChanged()` / `userEventTriggered()` 等方法很难在单元测试中触发 — 用集成测试验证
- 引用计数泄漏在测试中不一定立即报错，可启用 `ResourceLeakDetector.setLevel(Level.PARANOID)` 在测试环境检测泄漏

```java
// 在 @BeforeAll 中启用泄漏检测
@BeforeAll
static void enableLeakDetection() {
    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
}
```

---

## 完成标准

- [ ] `./gradlew test` — 全部通过
- [ ] `./gradlew build` — 编译无错误
- [ ] 新功能有对应的集成测试
- [ ] 安全相关变更有证书验证场景测试
- [ ] 性能变更有 JMH 对比数据
