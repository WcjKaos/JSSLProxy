# Security Rules — JSSLProxy (mTLS 安全代理)

## 这是一个安全基础设施产品 — 零容忍安全错误

---

## TLS / mTLS 配置

### 强制要求
- 最低支持 TLS 1.2，推荐默认 TLS 1.3
- NEVER 添加弱密码套件：RC4、DES、3DES、EXPORT、NULL、anon
- NEVER 禁用主机名验证（`ALLOW_ALL` HostnameVerifier）
- 握手超时 MUST 配置（防慢速握手攻击），默认 10 秒
- 客户端证书验证模式：生产环境必须为 `REQUIRED`，NEVER 在生产用 `NONE`

### 后端连接
- 后端 HTTPS 连接 MUST 验证服务端证书（hostname + chain）
- trustStore 配置必须生效（`CUSTOM` 或 `BOTH`），NEVER 信任所有证书
- 后端地址 MUST 通过 SSRF 校验（拒绝私有地址、localhost、云元数据地址）

---

## 证书处理

### 敏感信息保护
- NEVER 在日志中输出完整证书内容、私钥、指纹明文
- NEVER 在 HTTP 响应中返回证书私钥或 keystore 内容
- 证书密码 MUST 通过环境变量注入（`KEYSTORE_PASSWORD`、`TRUSTSTORE_PASSWORD`），NEVER 硬编码

### 证书验证流程
必须按顺序执行（`SSLAuthHandler` 实现）：
1. 验证证书链有效性
2. 检查有效期
3. 检查黑名单（指纹）
4. 检查白名单（指纹）
5. 检查 OCSP/CRL 吊销状态

NEVER 跳过任何一步，NEVER 在验证失败时继续处理请求。

### 证书吊销检查（SSRF 防护）
- OCSP/CRL URL 必须通过 `isValidRevocationUrl()` 验证
- 拒绝以下目标：私有 IP、127.x、10.x、172.16-31.x、192.168.x、::1、fc/fd 前缀、CGNAT（100.64-127.*）
- OCSP 响应 MUST 验证签名（使用 issuer 证书）
- CRL 文件 MUST 有大小限制（防 OOM 攻击）

---

## 管理端口

- 管理端口（默认 9090）MUST 绑定到 localhost（`127.0.0.1`）
- MUST 启用 Token 认证（`ManagementAuthHandler`）
- 生产环境中 token MUST 通过环境变量配置，NEVER 使用默认值
- 启动时检测到不安全配置（如监听 0.0.0.0 但无认证）MUST 拒绝启动

---

## 限流与 DoS 防护

- 全局限流和客户端级限流 MUST 同时启用
- 限流参数热加载时 MUST 保证令牌桶平滑迁移（不能清零已有令牌）
- `MaxConnectionsHandler` 必须在 Pipeline 最前面，超限时尽早拒绝

---

## 输入验证

- 所有来自外部的 HTTP Header MUST 检查长度和合法字符
- `X-Forwarded-For` 追加时 MUST 清理前缀/后缀空白
- Admin API 接收的指纹值 MUST 验证格式（hex 字符串，64 个字符 SHA-256）
- NEVER 将外部输入直接拼接到命令、文件路径或 URL

---

## 密钥与 Secret 管理

- NEVER 将 keystore、truststore、密码提交到 git
- 证书文件路径在配置中，实际文件在 `certs/` 目录（已加入 .gitignore）
- 审计日志中记录证书指纹（fingerprint），而不是完整的证书 DN

---

## 安全变更评审要求

以下变更 MUST 经过多 agent 辩证评审（见 `review.md`）：
- 修改 SSL 上下文配置（`SSLContextFactory`、`SslContextHolder`）
- 修改证书验证逻辑（`SSLAuthHandler`、`ClientAuthenticator`）
- 修改吊销检查逻辑（`CertificateRevocationChecker`）
- 修改管理端口认证（`ManagementAuthHandler`）
- 添加新的 Pipeline Handler（可能影响安全顺序）
