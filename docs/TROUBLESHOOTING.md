# 故障排查手册

> 排查顺序：先确认进程和管理端点，再看 TLS/mTLS，再看后端转发，最后看限流、熔断和性能。

## 基础检查

Linux/macOS：

```bash
curl -i http://localhost:9090/health
curl -i http://localhost:9090/ready
curl -s http://localhost:9090/metrics | head
```

Windows PowerShell：

```powershell
curl.exe -i http://localhost:9090/health
curl.exe -i http://localhost:9090/ready
curl.exe -s http://localhost:9090/metrics
```

Docker：

```bash
docker compose ps
docker compose logs --tail=120 sslproxy
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
```

## `/health` 不通

现象：连接被拒绝、超时或容器 unhealthy。

常见原因：

- 进程未启动或端口映射不一致。
- Docker 配置中的 `server.port` 与 `ports` 不匹配。
- 管理端口只监听容器内 `127.0.0.1`，宿主机无法访问。

处理：

- 确认 `docker-compose.yml` 使用 `18443:18443` 和 `127.0.0.1:9090:9090`。
- Docker 部署中如需宿主机访问管理端口，设置 `management.host: 0.0.0.0`，并配置 `MANAGEMENT_TOKEN`。

## `/ready` 返回 503

现象：`/health` 为 200，但 `/ready` 为 503。

常见原因：

- 代理处于 drain 模式。
- 所有后端都不健康。
- TLS 上下文未初始化或证书过期。

确认：

```bash
curl -i http://localhost:9090/ready
curl -H "Authorization: Bearer $MANAGEMENT_TOKEN" http://localhost:9090/admin/certs
```

处理：

- 如为 drain，调用 `DELETE /admin/drain` 或等待发布流程完成。
- 检查 `target.host`、`target.port`、`target.protocol`。
- 检查证书路径、密码和有效期。

## mTLS 请求失败

现象：curl TLS 握手失败、连接被关闭、指标出现 `proxy_ssl_handshakes_failures_total`。

确认：

```bash
curl -vk --cert src/main/resources/certificates/trusted/client-full.pem \
  --cacert src/main/resources/certificates/ca.crt \
  https://localhost:18443/
curl -s http://localhost:9090/metrics | grep proxy_ssl_handshakes_failures_total
```

Windows：

```powershell
curl.exe -vk --cert src/main/resources/certificates/trusted/client-full.pem --cacert src/main/resources/certificates/ca.crt https://localhost:18443/
curl.exe -s http://localhost:9090/metrics
```

常见原因和处理：

- `reason="no_cert"`：客户端未提供证书；curl 增加 `--cert`。
- `reason="untrusted"`：客户端证书不在 truststore 信任链内；更新 truststore。
- `reason="expired"`：证书过期；轮换证书。
- `reason="revoked"`：OCSP/CRL 判定吊销；确认吊销源和证书状态。
- 弱密钥或弱签名：使用 RSA 2048+ 或 ECDSA P-256+，避免 SHA-1/MD5。

被拒绝证书可通过：

```bash
curl -H "Authorization: Bearer $MANAGEMENT_TOKEN" \
  "http://localhost:9090/admin/rejected-certs?limit=20"
```

## 后端返回 502/503

现象：mTLS 握手成功，但代理返回 `502 Bad Gateway` 或 `/ready` 后端 DOWN。

常见原因：

- 后端地址或端口错误。
- 后端 TLS 证书 hostname mismatch。
- 后端 DNS 不可解析。
- 连接池耗尽或熔断器打开。

确认：

```bash
curl -s http://localhost:9090/metrics | grep proxy_connections_errors_total
curl -H "Authorization: Bearer $MANAGEMENT_TOKEN" http://localhost:9090/admin/stats
```

处理：

- 检查 `target.host`、`target.port`、`target.protocol`。
- HTTPS 后端优先使用正确 DNS 名称，避免用 IP 访问证书只包含域名的后端。
- 如果使用自签后端证书，按需设置 `ssl.backendTrustStrategy: CUSTOM` 或 `BOTH` 并提供 truststore。

## 管理 API 返回 401 或 403

现象：`/admin/*` 请求失败。

原因：

- `401`：token 未传或错误。
- `403`：`MANAGEMENT_TOKEN` 未配置，Admin API 被禁用。

处理：

```bash
export MANAGEMENT_TOKEN='replace-with-random-token'
curl -H "Authorization: Bearer $MANAGEMENT_TOKEN" http://localhost:9090/admin/info
```

Windows：

```powershell
$env:MANAGEMENT_TOKEN = "replace-with-random-token"
curl.exe -H "Authorization: Bearer $env:MANAGEMENT_TOKEN" http://localhost:9090/admin/info
```

## reload 失败

现象：`POST /admin/reload?target=certs` 返回 `500`，响应为 `Certificate reload failed`。

处理：

- 查看服务端日志中的详细异常。
- 检查证书文件路径、格式和密码。
- 确认证书目录挂载到了容器内 `/app/certs`。
- HTTP 响应不会返回底层异常细节，这是为了避免泄露密钥库路径或密码相关信息。

## 本机 Gradle 失败

现象：Windows 上 `.\gradlew.bat test` 在测试前失败，错误中包含 Java `25.0.2`。

处理：

- 切换 Java 17。
- 或使用 Docker 验证：

```powershell
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew test
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew build
```

## 指标为空或不可用

现象：`/metrics` 为空或返回不可用信息。

处理：

- 确认 `metrics.enabled: true`。
- 确认访问的是管理端口 `9090`，不是代理端口 `18443`。
- 使用 Docker smoke test 重新验证基础路径。
