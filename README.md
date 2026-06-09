# Java SSL Proxy

> 基于 Netty 的轻量级 mTLS 安全网关，用于 TLS 终止、HTTP/HTTPS 转发、客户端证书准入控制和运行时可观测性。

**[English](README_EN.md)**

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Netty](https://img.shields.io/badge/Netty-4.1+-green.svg)](https://netty.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 项目定位

JSSLProxy 专注于单体、轻量、易部署的 mTLS 反向代理能力。它适合在 Java/自建基础设施场景中为后端服务增加客户端证书认证、证书黑白名单、吊销检查、限流、熔断、Prometheus 指标和管理 API。

当前范围不包含通用 API 网关、Service Mesh、动态路由引擎、完整 HTTP/2 代理、L4 透明代理、复杂 RBAC 或证书生命周期平台。

## 5 分钟验证

推荐先跑 Docker smoke test。它会在 `build/smoke-test/` 生成临时证书和配置，启动 nginx 后端与代理，并验证 mTLS 成功请求、无证书拒绝、`/health`、`/ready`、`/metrics` 和 `/admin/*` 未授权拒绝。

```powershell
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
docker compose -f docker-compose.test.yml down --remove-orphans

# Windows PowerShell wrapper
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1
```

## 本地开发

要求 Java 17。当前宿主机 JDK 25 不是项目验收基线；如本机 Java 版本不匹配，使用上面的 Docker 命令。

```powershell
# Windows
.\scripts\generate-certs.ps1
$env:KEYSTORE_PASSWORD = "changeit"
$env:TRUSTSTORE_PASSWORD = "changeit"
.\gradlew.bat run
```

```bash
# Linux/macOS
(cd scripts && ./generate-certs.sh)
export KEYSTORE_PASSWORD=changeit
export TRUSTSTORE_PASSWORD=changeit
./gradlew run
```

默认 `src/main/resources/application.yml` 是开发样例，代理端口为 `18443`，管理端口为 `9090`，且 `ssl.clientAuth: NONE`。如果要本机强制 mTLS，请先把 `ssl.clientAuth` 改为 `REQUIRED`，再使用客户端证书访问：

```bash
curl --cert src/main/resources/certificates/trusted/client-full.pem \
  --cacert src/main/resources/certificates/ca.crt \
  https://localhost:18443/
```

## Docker 部署

生产 compose 使用 `docker-compose.yml`，代理端口映射为 `18443:18443`，管理端口默认只发布到宿主机 `127.0.0.1:9090`。

```bash
mkdir -p config certs data
cp src/main/resources/application.yml config/application.yml
cp -r src/main/resources/certificates/* certs/

# 按下方生产清单编辑 config/application.yml 后再启动

export KEYSTORE_PASSWORD='replace-with-strong-password'
export TRUSTSTORE_PASSWORD='replace-with-strong-password'
export MANAGEMENT_TOKEN='replace-with-random-token'

docker compose up -d --build
```

生产前必须修改 `config/application.yml`：

- `ssl.clientAuth: REQUIRED`，除非明确要关闭客户端证书认证。
- `ssl.keystore.path` 和 `ssl.truststore.path` 指向 `/app/certs/...` 下的挂载文件。
- 不要使用 `changeit`；密码通过 `KEYSTORE_PASSWORD`、`TRUSTSTORE_PASSWORD` 注入。
- 如需从容器外访问管理端口，设置 `management.host: 0.0.0.0`，并必须配置 `MANAGEMENT_TOKEN` 和网络访问控制。
- 如需动态黑白名单持久化，设置 `security.dynamicListPersistencePath: /app/data/dynamic-lists.json`。

详细部署清单见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

## 管理端点

管理服务默认监听 `9090`。

| 端点 | 鉴权 | 说明 |
|------|------|------|
| `GET /health` | 否 | 存活检查 |
| `GET /ready` | 否 | 就绪检查；drain、后端全 DOWN、证书异常时返回 503 |
| `GET /metrics` | 否 | Prometheus 指标 |
| `GET /admin/ui` | 否 | 静态管理 UI 壳页面；页面内数据请求仍需 token |
| `GET /admin/info` | 是 | 版本、配置哈希、运行时信息 |
| `/admin/*` | 是 | 黑白名单、连接终止、reload、drain、证书自省等管理 API |

浏览器操作界面：

```bash
open http://localhost:9090/admin/ui
```

页面只在 `sessionStorage` 保存 token，所有运行时数据仍通过受保护的 `/admin/*` API 获取。详见 [docs/ADMIN-UI.md](docs/ADMIN-UI.md)。

Admin API 使用：

```bash
TOKEN="$MANAGEMENT_TOKEN"
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/info
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/certs
curl -X POST -H "Authorization: Bearer $TOKEN" --data-binary "$FINGERPRINT" \
  http://localhost:9090/admin/blacklist
```

完整端点说明见 [docs/ADMIN-API.md](docs/ADMIN-API.md)。

命令行管理：

```bash
./gradlew jar
JSSLPROXY_ADMIN_URL=http://localhost:9090 \
JSSLPROXY_ADMIN_TOKEN="$MANAGEMENT_TOKEN" \
./scripts/jsslproxyctl.sh status
```

CLI 适合 SSH、脚本和 CI 场景；写操作需要 `--yes`。详见 [docs/CLI.md](docs/CLI.md)。

## 指标与排障

Prometheus 指标端点：

```bash
curl http://localhost:9090/metrics
```

常用指标包括 `proxy_requests_total`、`proxy_requests_errors_total`、`proxy_connections_active`、`proxy_connections_errors_total{cause="..."}`、`proxy_ssl_handshakes_failures_total{reason="..."}`、`proxy_request_duration_seconds_bucket` 和 `proxy_ssl_cert_expiry_seconds{type="server"}`。

详细指标表见 [docs/METRICS.md](docs/METRICS.md)，常见问题处理见 [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。

## 测试

日常验证优先使用 Docker JDK 17：

```bash
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew test
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew build
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
docker compose -f docker-compose.test.yml down --remove-orphans
```

性能基线：

```bash
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew jmh
bash scripts/perf-baseline.sh
```

测试环境策略见 [docs/TEST-ENVIRONMENT.md](docs/TEST-ENVIRONMENT.md)，覆盖矩阵见 [docs/TEST-MATRIX.md](docs/TEST-MATRIX.md)。
发布门禁见 [docs/RELEASE-CHECKLIST.md](docs/RELEASE-CHECKLIST.md)，最终验收见 [docs/RELEASE-READINESS.md](docs/RELEASE-READINESS.md)。

## 项目结构

```text
JSSLProxy/
├── src/main/java/com/sslproxy/
│   ├── server/        # 代理启动、Netty transport、上下文
│   ├── handler/       # TLS、认证、限流、访问日志、转发 Handler
│   ├── client/        # 后端连接与连接池
│   ├── security/      # 证书验证、吊销检查、动态名单
│   ├── management/    # /health、/ready、/metrics、/admin/*
│   ├── metrics/       # Micrometer 指标
│   ├── ratelimit/     # Bucket4j 限流
│   ├── resilience/    # 熔断器
│   └── audit/         # 审计日志
├── src/test/java/     # JUnit 5 单元与集成测试
├── src/jmh/java/      # JMH benchmark
├── scripts/           # 证书、smoke、性能脚本
├── docs/              # 部署、API、指标、排障、测试文档
├── Dockerfile
├── docker-compose.yml
└── docker-compose.test.yml
```

## 许可证

[MIT License](LICENSE)
