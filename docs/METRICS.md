# 指标手册

> `/metrics` 暴露 Prometheus 文本格式。Micrometer 内部使用点号名称，Prometheus 输出会转换为下划线和 `_total`、`_seconds` 等后缀。以下名称来自 Docker smoke test 的实际输出。

## 访问方式

```bash
curl http://localhost:9090/metrics
```

Windows PowerShell：

```powershell
curl.exe http://localhost:9090/metrics
```

## 核心指标

| Prometheus 指标 | 类型 | 标签 | 含义 |
|-----------------|------|------|------|
| `proxy_connections_active` | Gauge | 无 | 当前活跃客户端连接 |
| `proxy_connections_total` | Counter | 无 | 客户端连接总数 |
| `proxy_connections_errors_total` | Counter | `cause` | 后端连接错误分类 |
| `proxy_requests_total` | Counter | 无 | 请求总数 |
| `proxy_requests_errors_total` | Counter | 无 | 请求错误总数 |
| `proxy_request_duration_seconds_bucket` | Histogram | `le` | 请求耗时桶 |
| `proxy_request_duration_seconds_count` | Histogram | 无 | 请求耗时样本数 |
| `proxy_request_duration_seconds_sum` | Histogram | 无 | 请求耗时总和 |
| `proxy_ssl_handshakes_total` | Counter | 无 | TLS 握手成功数 |
| `proxy_ssl_handshakes_failures_total` | Counter | `reason` | TLS 握手失败分类 |
| `proxy_ssl_handshake_duration_seconds_count` | Summary | 无 | 握手耗时样本数 |
| `proxy_ssl_cert_expiry_seconds` | Gauge | `type` | 服务端/CA 证书剩余秒数 |
| `proxy_ratelimit_total` | Counter | 无 | 限流总次数 |
| `proxy_ratelimit_global_total` | Counter | 无 | 全局限流次数 |
| `proxy_ratelimit_client_total` | Counter | 无 | 客户端级限流次数 |
| `proxy_pool_active` | Gauge | 无 | 后端连接池活跃连接 |
| `proxy_pool_idle` | Gauge | 无 | 后端连接池空闲连接 |
| `proxy_pool_acquire_timeouts_total` | Counter | 无 | 连接池获取超时 |
| `proxy_circuitbreaker_state` | Gauge | 无 | 熔断状态：0 closed，1 open，2 half-open |
| `proxy_circuitbreaker_calls_total` | Counter | `result` | 熔断器调用结果 |
| `proxy_circuitbreaker_state_transitions_total` | Counter | 无 | 熔断状态切换次数 |
| `proxy_bytes_received_bytes` | Gauge | 无 | 从客户端接收的字节累计值 |
| `proxy_bytes_sent_bytes` | Gauge | 无 | 发给客户端的字节累计值 |
| `proxy_uptime_seconds` | Gauge | 无 | 进程运行秒数 |

## 标签取值

`proxy_connections_errors_total{cause=...}`：

- `timeout`
- `refused`
- `tls`
- `dns`
- `pool_exhausted`
- `circuit_open`
- `other`

`proxy_ssl_handshakes_failures_total{reason=...}`：

- `expired`
- `revoked`
- `untrusted`
- `cipher_mismatch`
- `protocol_mismatch`
- `no_cert`
- `timeout`
- `other`

## 常用 PromQL

`proxy_circuitbreaker_calls_total{result="successful|failed|rejected"}` 在完整后端响应、失败或拒绝时更新。HTTP `5xx`、连接失败、响应中断和超时计入 `failed`；其他最终 HTTP 响应计入 `successful`。客户端取消不计入成功或失败，`1xx` 临时响应也不结算。慢调用根据请求获准至完整响应的总耗时判定，可通过 `resilience4j_circuitbreaker_slow_calls` 观察；请求写出成功不会提前增加成功计数。

请求速率：

```promql
rate(proxy_requests_total[5m])
```

请求错误率：

```promql
rate(proxy_requests_errors_total[5m]) / clamp_min(rate(proxy_requests_total[5m]), 1)
```

P95 延迟：

```promql
histogram_quantile(0.95, rate(proxy_request_duration_seconds_bucket[5m]))
```

TLS 握手失败率：

```promql
sum(rate(proxy_ssl_handshakes_failures_total[5m]))
/
clamp_min(sum(rate(proxy_ssl_handshakes_total[5m])), 1)
```

后端连接错误分布：

```promql
sum by (cause) (rate(proxy_connections_errors_total[5m]))
```

证书剩余天数：

```promql
proxy_ssl_cert_expiry_seconds{type="server"} / 86400
```

## 观察建议

- 首先看 `proxy_requests_total` 是否增长，确认流量进入代理。
- mTLS 问题看 `proxy_ssl_handshakes_failures_total` 的 `reason`。
- 后端不可达看 `proxy_connections_errors_total` 的 `cause` 和代理响应码。
- 延迟问题看 `proxy_request_duration_seconds_bucket`，并结合后端日志确认是否是代理或后端瓶颈。
- 证书轮换后看 `/admin/certs` 和 `proxy_ssl_cert_expiry_seconds`。
