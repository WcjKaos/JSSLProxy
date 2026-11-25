# 部署指南

> 范围：JSSLProxy 1.3.0 质量收口。本文说明如何从开发样例走到可上线的 Docker/单 JAR 部署。

## 前置条件

- 本地开发：Java 17、Gradle Wrapper、OpenSSL、curl。
- 推荐验证：Docker Desktop/Engine 和 Docker Compose。
- 当前验收基线：`gradle:8.14-jdk17`。宿主机 JDK 25 已知不能作为构建验收环境。

## 快速可用性验证

```bash
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
docker compose -f docker-compose.test.yml down --remove-orphans
```

该命令会生成临时证书和配置，验证真实 mTLS 请求、拒绝无证书请求、健康检查、metrics、admin 鉴权、管理 UI 静态壳页面和 CLI 基础命令。它是本地/CI 验证脚本，不是生产部署脚本。

## 生产配置清单

`src/main/resources/application.yml` 是开发样例，包含 `clientAuth: NONE` 和 `changeit` 占位密码。生产必须调整：

```yaml
server:
  host: 0.0.0.0
  port: 18443

target:
  host: backend.internal
  port: 443
  protocol: https

ssl:
  keystore:
    path: /app/certs/server/server.p12
    password: ""       # KEYSTORE_PASSWORD 覆盖
    type: PKCS12
  truststore:
    path: /app/certs/trusted/truststore.p12
    password: ""       # TRUSTSTORE_PASSWORD 覆盖
    type: PKCS12
  clientAuth: REQUIRED
  backendTrustStrategy: SYSTEM
  revocation:
    enabled: false
    softFail: false

management:
  enabled: true
  host: 0.0.0.0        # Docker 发布端口时需要监听容器网卡
  port: 9090
  authEnabled: true
  authToken: ""        # MANAGEMENT_TOKEN 覆盖

security:
  dynamicListPersistencePath: /app/data/dynamic-lists.json
```

生产原则：

- 不要提交真实证书、私钥、token 或密码。
- 不要使用 `changeit`；使用 `KEYSTORE_PASSWORD`、`TRUSTSTORE_PASSWORD`、`MANAGEMENT_TOKEN` 注入敏感值。
- `ssl.clientAuth` 默认应为 `REQUIRED`。只有明确允许无客户端证书时才设为 `NONE`。
- 管理端口可以在容器内监听 `0.0.0.0`，但宿主机发布应限制为 `127.0.0.1:9090` 或受控内网。
- 若启用动态黑白名单，`/app/data` 必须可写；证书目录仍应只读挂载。

## Docker Compose

```bash
mkdir -p config certs data
cp src/main/resources/application.yml config/application.yml
cp -r src/main/resources/certificates/* certs/

export KEYSTORE_PASSWORD='replace-with-strong-password'
export TRUSTSTORE_PASSWORD='replace-with-strong-password'
export MANAGEMENT_TOKEN='replace-with-random-token'

docker compose up -d --build
docker compose ps
curl http://localhost:9090/health
curl -H "Authorization: Bearer $MANAGEMENT_TOKEN" http://localhost:9090/admin/info
open http://localhost:9090/admin/ui
```

`docker-compose.yml` 默认发布：

| 端口 | 用途 |
|------|------|
| `18443:18443` | mTLS 代理入口 |
| `127.0.0.1:9090:9090` | 管理端点，仅宿主机 loopback |
| `9091:9090` | Prometheus UI，需 `--profile monitoring` |
| `3000:3000` | Grafana UI，需 `--profile monitoring` |

启动监控：

```bash
docker compose --profile monitoring up -d
```

## 单 JAR 部署

```bash
./gradlew jar
export KEYSTORE_PASSWORD='replace-with-strong-password'
export TRUSTSTORE_PASSWORD='replace-with-strong-password'
export MANAGEMENT_TOKEN='replace-with-random-token'
java -jar build/libs/JSSLProxy-1.0-SNAPSHOT.jar /path/to/application.yml
```

使用 systemd、supervisor 或容器平台管理进程生命周期。发布前至少运行 `./gradlew test`、`./gradlew build`、Docker smoke test 和最小性能基线。

## 管理 UI 与 CLI

管理 UI 地址：

```bash
http://localhost:9090/admin/ui
```

浏览器页面只保存 session 级 token，所有运行时数据仍通过受保护的 `/admin/*` API 获取。生产环境仍应把管理端口限制在 loopback、堡垒机或受控内网。

CLI 适合 SSH、脚本和 CI：

```bash
./scripts/jsslproxyctl.sh --url http://localhost:9090 --token "$MANAGEMENT_TOKEN" status
./scripts/jsslproxyctl.sh --url http://localhost:9090 --token "$MANAGEMENT_TOKEN" blacklist list --json
```

更多说明见 `docs/ADMIN-UI.md` 和 `docs/CLI.md`。

## Kubernetes 探针

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 9090
  initialDelaySeconds: 10
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /ready
    port: 9090
  initialDelaySeconds: 5
  periodSeconds: 5
```

`/ready` 在 drain、后端全部不可用或 TLS 上下文异常时返回 `503`，适合作为流量摘除信号。

未启用 `target.healthCheck` 时，每次 `/ready` 会并行探测后端 TCP 可达性，超时为 2 秒，并发请求共享在途探测。此探测不会修改负载均衡路由状态；至少一个后端可达才返回 `200`，部分可达时 `checks.backendTarget.status` 为 `PARTIAL`。

需要验证后端 HTTP 服务和 HTTPS 证书时，启用周期健康检查：

```yaml
target:
  host: backend.example.com
  port: 443
  protocol: https
  healthCheck:
    enabled: true
    path: /health
    intervalSeconds: 10
    timeoutMs: 2000
    unhealthyThreshold: 3
    healthyThreshold: 2
```

启用后，`/ready` 使用周期检查结果，首个成功探测前返回 `503`。HTTPS 检查沿用 `ssl.backendTrustStrategy`、信任库和主机名验证，探测超时包含 DNS、连接、TLS 握手及 HTTP 响应。

熔断器按完整后端响应结算：HTTP `5xx`、连接或写入失败、响应中断和超时计为失败；其他最终响应计为成功，响应状态及正文继续透传。`1xx` 临时响应不会提前结算，客户端主动取消不计为后端失败。慢调用耗时从请求获准转发开始，覆盖连接获取和完整响应等待。`connection.timeout` 同时限定单次完整后端响应的总等待时间，超时返回 `504`。
