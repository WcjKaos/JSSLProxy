# Task Management & Workflow — JSSLProxy

## 任务启动前的三问

在开始任何非微小任务前，确认：
1. **WHAT** — 期望的具体输出是什么（功能、修复、性能指标）
2. **WHERE** — 哪些模块/文件需要修改（Handler？Config？Security？）
3. **CONSTRAINTS** — 哪些不能动（Pipeline 顺序、安全机制、公共接口）

无法回答这三个问题 → 先与用户对齐，不要开始写代码。

---

## 工作流程

```
1. 读取并理解目标文件（NEVER 基于记忆猜测代码内容）
2. 非微小变更 → 进入 Plan Mode
   - 分析受影响文件和 Handler
   - 确认 Pipeline 顺序不被破坏
   - 设计测试用例（见 testing.md）
   - 安全变更 → 触发辩证评审（见 review.md）
   - 获得用户批准后开始
3. 先写测试（Red phase）
4. 实现代码（Green phase）
5. 运行全量测试：./gradlew test
6. 运行构建：./gradlew build
7. Commit（conventional commit format）
```

---

## 哪些变更需要特别谨慎

### 高风险 — MUST 进入 Plan Mode + 多 agent 评审（见 review.md）
- 修改 Netty Pipeline 顺序（ServerInitializer）
- 修改任何安全 Handler（SslHandler、SSLAuthHandler、ClientAuthenticator）
- 修改证书吊销检查逻辑
- 修改 ByteBuf 引用计数相关代码
- 修改连接池的 acquire/release 逻辑

### 中风险 — 进入 Plan Mode，仔细测试
- 添加新的 Pipeline Handler
- 修改限流/熔断配置逻辑
- 修改配置热加载（ConfigWatcher/SslContextHolder）
- 修改管理端口接口

### 低风险 — 直接实现，但 MUST 跑测试
- 修改日志级别/格式
- 新增指标（ProxyMetrics）
- 更新文档和配置注释
- 修改测试代码

---

## Netty 专属检查项

每次修改 Handler 代码后检查：
- [ ] 新代码没有在 EventLoop 中阻塞（见 netty.md）
- [ ] 每处 `retain()` 都有对应的 `release()` 或 `writeAndFlush()`
- [ ] 错误路径（连接失败、写失败）正确释放了 ByteBuf
- [ ] 关闭前端连接时同时处理了后端连接
- [ ] 新 Handler 是否需要 `@ChannelHandler.Sharable` 标注（见 netty.md）

---

## Commit 规范

使用 Conventional Commits 格式：

```
<type>(<scope>): <description>

feat(security): 添加证书有效期预警机制
fix(handler): 修复连接超时时 ByteBuf 未释放的内存泄漏
perf(pool): 优化连接池 acquire 在高并发下的竞争
test(mtls): 补充 CRL 吊销场景的集成测试
refactor(ratelimit): 简化热加载时令牌桶迁移逻辑
docs(architecture): 更新 Pipeline 顺序说明
```

Scope 参考：`security`、`handler`、`config`、`pool`、`ratelimit`、`circuit`、`lb`、`mgmt`、`metrics`

---

## 环境检查

运行项目前：
```bash
export KEYSTORE_PASSWORD=changeit
export TRUSTSTORE_PASSWORD=changeit
# 证书不存在时先生成
.\scripts\generate-certs.ps1   # Windows
./scripts/generate-certs.sh    # Linux/Mac
```

验证构建正常：
```bash
./gradlew build            # 编译 + 单元测试
./gradlew test             # 仅跑测试
./gradlew jmh              # 性能基准（提交性能变更时运行）
```
