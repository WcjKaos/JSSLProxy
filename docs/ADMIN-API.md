# 管理 API 手册

> 管理服务默认监听 `9090`。`/health`、`/ready`、`/metrics` 不需要 token。`/admin/ui` 是静态 UI 壳页面，不包含运行时数据；除该壳页面外，所有 `/admin/*` JSON 端点都由 `ManagementAuthHandler` 保护。

## 鉴权

推荐使用 Bearer token：

```bash
TOKEN="$MANAGEMENT_TOKEN"
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/info
```

也支持 `X-Admin-Token: <token>`。未配置 token 时 `/admin/*` 返回 `403`；token 缺失或错误时返回 `401` 并记录 `ADMIN_UNAUTHORIZED` 审计事件。

## 公共端点

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/health` | 存活检查，进程可响应即返回 `200` |
| `GET` | `/ready` | 就绪检查；drain、后端全部 DOWN、证书异常时返回 `503` |
| `GET` | `/metrics` | Prometheus 文本格式指标 |
| `GET` | `/admin/ui` | 轻量管理 UI 静态壳页面；页面内数据请求仍需 token |

## 管理端点

| 方法 | 路径 | 参数/请求体 | 审计 |
|------|------|-------------|------|
| `GET` | `/admin/info` | 无 | 无 |
| `GET` | `/admin/stats` | 无 | 无 |
| `GET` | `/admin/certs` | 无 | 无 |
| `GET` | `/admin/rejected-certs?limit=50` | `limit` 范围 1-500 | 无 |
| `GET` | `/admin/whitelist` | 无 | 无 |
| `POST` | `/admin/whitelist` | 请求体为 SHA-256 指纹 | `ADMIN_WHITELIST_ADD` |
| `DELETE` | `/admin/whitelist` | 请求体为 SHA-256 指纹 | `ADMIN_WHITELIST_REMOVE` |
| `GET` | `/admin/blacklist` | 无 | 无 |
| `POST` | `/admin/blacklist` | 请求体为 SHA-256 指纹 | `ADMIN_BLACKLIST_ADD` |
| `DELETE` | `/admin/blacklist` | 请求体为 SHA-256 指纹 | `ADMIN_BLACKLIST_REMOVE` |
| `GET` | `/admin/connections` | 无 | 无 |
| `DELETE` | `/admin/connections?fingerprint=<sha256>` | `fingerprint` query 参数 | `ADMIN_CONNECTION_TERMINATE` |
| `POST` | `/admin/reload?target=config\|certs\|all` | 默认 `all` | `ADMIN_RELOAD` |
| `POST` | `/admin/drain?timeout=30` | `timeout` 秒，可选 | `ADMIN_DRAIN_START` |
| `DELETE` | `/admin/drain` | 无 | `ADMIN_DRAIN_CANCEL` |

## 常用示例

打开管理 UI：

```bash
open http://localhost:9090/admin/ui
```

CLI 查看状态：

```bash
JSSLPROXY_ADMIN_URL=http://localhost:9090 \
JSSLPROXY_ADMIN_TOKEN="$MANAGEMENT_TOKEN" \
./scripts/jsslproxyctl.sh status
```

获取服务信息。注意实际路径是 `/admin/info`，不是 `/info`：

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/info
```

查看当前证书：

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/certs
```

添加黑名单。指纹放在请求体中，支持冒号分隔或 64 位十六进制：

```bash
FINGERPRINT="AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
curl -X POST -H "Authorization: Bearer $TOKEN" --data-binary "$FINGERPRINT" \
  http://localhost:9090/admin/blacklist
```

终止已建立连接：

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/admin/connections?fingerprint=$FINGERPRINT"
```

手动重载配置或证书：

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/admin/reload?target=config
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/admin/reload?target=certs
```

进入和退出 drain：

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/admin/drain?timeout=30
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/admin/drain
```

## 响应和错误码

- `200`：请求成功。
- `400`：参数缺失或格式错误，例如连接终止缺少 `fingerprint`。
- `401`：token 缺失或错误。
- `403`：admin token 未配置，管理 API 被禁用。
- `404`：路径不存在。
- `405`：HTTP 方法不支持。
- `500`：内部错误；证书 reload 失败只返回通用错误，不暴露底层密钥库、路径或密码细节。

所有写操作都会写入审计日志，包含来源 IP、操作详情和结果。
