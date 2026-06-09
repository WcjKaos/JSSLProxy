# 测试环境策略

> 目标：把 JSSLProxy 的核心验证尽量收敛到 Docker 环境，降低本机差异；少数必须体现宿主机体验或 CI 平台行为的检查保留专项验证。

## 总体原则

- 核心功能验证优先 Docker 化：构建、单元测试、集成测试、证书生成、代理启动、测试后端、mTLS curl、管理 API、metrics、k6 baseline。
- Docker 验证用于“可复现”和“回归对比”，不作为生产性能 SLA。
- 当前确定基线使用 `gradle:8.14-jdk17`；本机 JDK 25 已知会在 Gradle/Kotlin DSL 解析阶段失败，不能作为项目当前验收依据。
- Windows PowerShell 脚本仍需在 Windows 宿主机至少验证一次；Linux 容器中的 `pwsh` 不能完全代表 Windows 用户体验。
- GitHub Actions workflow 位于 `.github/workflows/ci.yml`。本地 Docker 等价命令已验证；最终仍以 Actions 平台执行结果为准。
- 所有测试不得依赖真实生产 secret、真实证书或外部私有服务。

## 当前已验证命令

在 Windows PowerShell 下，以下 Docker 命令已作为当前质量基线：

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew build
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "*HandlerTest"
```

针对曾失败的代理集成路径，已验证：

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "com.sslproxy.integration.ProxyForwardingIT" --tests "com.sslproxy.integration.CircuitBreakerIT" --tests "com.sslproxy.integration.BackendHostnameVerificationIT" --tests "com.sslproxy.integration.DynamicCertificateManagementIT"
```

后续新增 `docker-compose.test.yml` 后，应优先用 Compose 封装这些命令，避免每次手写 volume、工作目录和镜像名。

当前已实现 Docker smoke test：

```powershell
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
docker compose -f docker-compose.test.yml down --remove-orphans
```

Windows PowerShell 入口：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1
```

该 smoke test 用于本地和 CI 验证，不是生产部署脚本。它会在 `build/smoke-test/` 生成临时证书、临时配置和日志，并通过 Compose 内的 nginx 后端验证代理启动、mTLS 成功请求、无证书拒绝、`/health`、`/ready`、`/metrics`、`/admin/*` 未授权拒绝、`/admin/ui` 静态壳页面，以及 CLI 的 status、certs、rejected-certs、blacklist add/remove、reload certs、drain start/cancel 和 metrics。

## 推荐 Docker 测试组成

`docker-compose.test.yml` 当前包含：

| 服务 | 用途 |
|------|------|
| `test-runner` | JDK 17、Gradle Wrapper、OpenSSL、curl；执行 `test`、`build`、smoke test |
| `backend` | nginx 测试后端，用于 smoke test 代理转发 |
| `k6` | 执行 `scripts/k6/*` 的基线和压力测试 |

建议命令形态：

```bash
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew test
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew build
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
docker compose -f docker-compose.test.yml --profile perf run --rm k6 run scripts/k6/baseline-test.js
docker compose -f docker-compose.test.yml build
```

性能基线推荐入口：

```bash
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew jmh
bash scripts/perf-baseline.sh
```

`scripts/perf-baseline.sh` 会复用 smoke test 证书生成逻辑，启动 Compose `backend` 和 `proxy` 服务，再通过 `grafana/k6` 以 quick mode 跑 mTLS baseline。

## 最低宿主机要求

即使使用 Docker，宿主机仍需要：

- Docker Desktop 或 Docker Engine
- Git
- 可用本地端口，避免与 `18443`、`9090`、测试后端端口冲突
- 首次构建所需网络访问，用于拉取镜像、Gradle 依赖、npm/k6 镜像

## 保留宿主机专项验证

以下项目不能只靠 Docker 判定完成：

- Windows 使用体验：`scripts/smoke-test.ps1`、README 中的 PowerShell 命令、`curl.exe` 示例
- CI 真实结果：GitHub Actions workflow 必须在远端至少成功一次
- 性能绝对值：JMH/k6 的 Docker 结果只做回归基线，发布前如需性能结论，应记录宿主机或目标环境结果

## 任务验收口径

- 日常开发：优先运行 Docker 化的 `test`、`build`、smoke test。
- 安全和 Netty 修复：除 Docker 全量测试外，需要按任务要求补专项测试或审计记录。
- 发布前：Docker 全量验证、Windows PowerShell smoke test、Docker build、最小 JMH/k6 baseline 均需完成；无法完成的项目必须在 `docs/RELEASE-READINESS.md` 记录原因和风险。
