# Java SSL Proxy

> 基于 Netty 构建的高性能 SSL/TLS 代理服务器，支持 mTLS 双向认证、证书白名单/黑名单管理

**[English](README_EN.md)**

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Netty](https://img.shields.io/badge/Netty-4.1+-green.svg)](https://netty.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 核心特性

- **双向 TLS 认证 (mTLS)** - 客户端和服务器相互验证
- **证书白名单/黑名单** - 细粒度的客户端访问控制
- **高性能** - 基于 Netty 异步事件驱动架构
- **Prometheus 指标** - 完整的可观测性支持
- **配置热加载** - 无需重启即可更新限流参数和证书指纹
- **后端连接池** - 高效的连接复用
- **限流保护** - 基于令牌桶的全局和客户端级别限流
- **Docker 支持** - 开箱即用的容器化部署

## 目录

- [系统要求](#系统要求)
- [快速开始](#快速开始)
- [Docker 部署](#docker-部署)
- [配置说明](#配置说明)
- [Prometheus 监控](#prometheus-监控)
- [管理端点](#管理端点)
- [证书管理](#证书管理)
- [测试](#测试)
- [常见问题](#常见问题)
- [项目结构](#项目结构)

## 系统要求

- Java 17+
- OpenSSL（用于证书生成）
- Gradle 7.0+（项目自带 wrapper）
- Docker & Docker Compose（可选，用于容器化部署）

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/WcjKaos/JSSLProxy.git
cd JSSLProxy
```

### 2. 生成测试证书

```powershell
# Windows
.\scripts\generate-certs.ps1

# Linux/Mac
./scripts/generate-certs.sh
```

### 3. 设置环境变量

```bash
# Linux/Mac
export KEYSTORE_PASSWORD=changeit
export TRUSTSTORE_PASSWORD=changeit

# Windows PowerShell
$env:KEYSTORE_PASSWORD="changeit"
$env:TRUSTSTORE_PASSWORD="changeit"
```

### 4. 启动服务器

```bash
./gradlew run
```

### 5. 测试连接

```powershell
# 使用受信任的客户端证书
curl.exe --cert src/main/resources/certificates/trusted/client-full.pem \
         --cacert src/main/resources/certificates/ca.crt \
         https://localhost:18443
```

## Docker 部署

### 快速启动

```bash
# 1. 准备配置和证书
mkdir -p config certs
cp src/main/resources/application.yml config/
cp -r src/main/resources/certificates/* certs/

# 2. 设置密码环境变量
export KEYSTORE_PASSWORD=your_secure_password
export TRUSTSTORE_PASSWORD=your_secure_password

# 3. 启动服务
docker-compose up -d
```

### 启动监控栈（可选）

```bash
# 包含 Prometheus + Grafana
docker-compose --profile monitoring up -d
```

### Docker Compose 服务

| 服务 | 端口 | 说明 |
|-----|------|------|
| sslproxy | 8443 | SSL 代理端口 |
| sslproxy | 9090 | 管理端口（指标/健康检查） |
| prometheus | 9091 | Prometheus UI（需启用 monitoring profile） |
| grafana | 3000 | Grafana 仪表板（需启用 monitoring profile） |

### 生产部署注意事项

1. **密码管理**：必须通过环境变量设置密码，不要使用默认值
2. **证书挂载**：将生产证书挂载到 `/app/certs` 目录
3. **配置挂载**：将配置文件挂载到 `/app/config/application.yml`
4. **资源限制**：根据实际负载调整 CPU 和内存限制

```yaml
# docker-compose.override.yml 示例
services:
  sslproxy:
    environment:
      JVM_MEMORY_OPTS: "-Xms512m -Xmx2g"
    deploy:
      resources:
        limits:
          cpus: '4'
          memory: 2048M
```

## 配置说明

### 基础配置 (`application.yml`)

```yaml
# 代理服务器监听配置
server:
  host: 0.0.0.0
  port: 18443

# 后端目标服务器
target:
  host: httpbin.org
  port: 443
  protocol: https

# SSL/TLS 配置
ssl:
  keystore:
    path: certificates/server/server.p12
    password: ""  # 通过 KEYSTORE_PASSWORD 环境变量设置
    type: PKCS12

  truststore:
    path: certificates/trusted/truststore.p12
    password: ""  # 通过 TRUSTSTORE_PASSWORD 环境变量设置
    type: PKCS12

  clientAuth: REQUIRED  # REQUIRED | OPTIONAL | NONE
  protocols: [TLSv1.3, TLSv1.2]
  handshakeTimeout: 10000  # SSL 握手超时（毫秒）

# 后端连接配置
connection:
  timeout: 30000
  keepalive: true

# 性能配置
performance:
  backlog: 1024
  workerThreads: 0  # 0 = CPU 核心数 * 2
  maxRequestSize: 10485760  # 10MB
  backendPool:
    enabled: true
    maxConnections: 100
    acquireTimeout: 5000
    maxIdleTime: 60000

# 管理端点配置
management:
  enabled: true
  host: 0.0.0.0
  port: 9090

# 指标收集配置
metrics:
  enabled: true
  includeJvmMetrics: true
  histogramBuckets: [5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000]

# 限流配置
rateLimit:
  enabled: false
  globalRps: 5000
  globalBurstCapacity: 500
  clientRps: 200
  clientBurstCapacity: 50

# 优雅关闭配置
shutdown:
  timeoutSeconds: 60
  awaitRequests: true
```

### 环境变量

| 环境变量 | 说明 | 必填 |
|---------|------|------|
| `KEYSTORE_PASSWORD` | 密钥库密码 | 是 |
| `TRUSTSTORE_PASSWORD` | 信任库密码 | 是 |
| `CONFIG_PATH` | 配置文件路径（Docker） | 否 |
| `JVM_MEMORY_OPTS` | JVM 内存参数（Docker） | 否 |

### 客户端认证模式

| 模式 | 说明 | 适用场景 |
|-----|------|---------|
| **REQUIRED** | 强制客户端证书 | 高安全内部系统 |
| **OPTIONAL** | 客户端证书可选 | 混合环境（部分认证） |
| **NONE** | 不需要客户端证书 | 公网服务 |

### 配置热加载

以下配置支持热更新（修改配置文件后自动生效，无需重启）：

- `rateLimit.*` - 限流参数
- `ssl.whitelistFingerprints` - 证书指纹白名单
- `ssl.blacklistFingerprints` - 证书指纹黑名单

不支持热更新的配置（需要重启）：

- `server.*` - 服务器端口
- `target.*` - 后端目标
- `ssl.keystore.*` - SSL 证书

## Prometheus 监控

### 访问指标

```bash
# 直接访问指标端点
curl http://localhost:9090/metrics
```

### 可用指标列表

#### 连接指标

| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `proxy.connections.active` | Gauge | 当前活跃连接数 |
| `proxy.connections.total` | Counter | 总连接数 |
| `proxy.connections.errors` | Counter | 连接错误数 |

#### 请求指标

| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `proxy.requests.total` | Counter | 总请求数 |
| `proxy.requests.errors` | Counter | 请求错误数 |
| `proxy.request.duration` | Timer | 请求处理耗时（直方图） |

#### SSL 指标

| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `proxy.ssl.handshakes.total` | Counter | SSL 握手总次数 |
| `proxy.ssl.handshakes.failures` | Counter | SSL 握手失败次数 |
| `proxy.ssl.handshake.duration` | Timer | SSL 握手耗时 |

#### 限流指标

| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `proxy.ratelimit.total` | Counter | 被限流的请求总数 |
| `proxy.ratelimit.global` | Counter | 全局限流请求数 |
| `proxy.ratelimit.client` | Counter | 客户端限流请求数 |

#### 连接池指标

| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `proxy.pool.active` | Gauge | 连接池活跃连接数 |
| `proxy.pool.idle` | Gauge | 连接池空闲连接数 |
| `proxy.pool.acquire.timeouts` | Counter | 连接获取超时次数 |

#### 流量指标

| 指标名称 | 类型 | 说明 |
|---------|------|------|
| `proxy.bytes.received` | Gauge | 接收字节数 |
| `proxy.bytes.sent` | Gauge | 发送字节数 |
| `proxy.uptime.seconds` | Gauge | 服务器运行时间（秒） |

### Prometheus 配置

创建 `config/prometheus.yml`：

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'jsslproxy'
    static_configs:
      - targets: ['sslproxy:9090']  # Docker 网络内
      # - targets: ['localhost:9090']  # 本地开发
    metrics_path: /metrics
    scrape_interval: 5s
```

### Grafana 仪表板

启动监控栈后，访问 Grafana：

1. 打开 http://localhost:3000
2. 默认登录：admin / admin
3. 添加 Prometheus 数据源：http://prometheus:9090
4. 导入仪表板或创建自定义面板

#### 推荐面板

**概览面板**：
```
sum(proxy.connections.active)  # 活跃连接数
rate(proxy.requests.total[5m]) # 每秒请求数 (QPS)
rate(proxy.requests.errors[5m]) / rate(proxy.requests.total[5m]) * 100  # 错误率
```

**延迟面板**：
```
histogram_quantile(0.50, rate(proxy_request_duration_seconds_bucket[5m]))  # P50
histogram_quantile(0.95, rate(proxy_request_duration_seconds_bucket[5m]))  # P95
histogram_quantile(0.99, rate(proxy_request_duration_seconds_bucket[5m]))  # P99
```

**SSL 面板**：
```
rate(proxy.ssl.handshakes.total[5m])  # SSL 握手速率
rate(proxy.ssl.handshakes.failures[5m]) / rate(proxy.ssl.handshakes.total[5m]) * 100  # 失败率
```

## 管理端点

管理服务器默认监听 9090 端口，提供以下端点：

### 健康检查

```bash
# 存活检查 - 返回 200 表示服务存活
curl http://localhost:9090/health

# 响应示例
{
  "status": "UP",
  "timestamp": 1704520800000
}
```

### 就绪检查

```bash
# 就绪检查 - 检查所有依赖是否就绪
curl http://localhost:9090/ready

# 响应示例
{
  "status": "UP",
  "checks": {
    "connectionTracker": "UP",
    "metrics": "UP",
    "backendPool": "UP",
    "backendTarget": {
      "host": "httpbin.org",
      "port": 443,
      "protocol": "https"
    }
  },
  "timestamp": 1704520800000
}
```

### 服务信息

```bash
curl http://localhost:9090/info

# 响应示例
{
  "name": "JSSLProxy",
  "version": "1.0.0",
  "server": {
    "host": "0.0.0.0",
    "port": 18443
  },
  "target": {
    "host": "httpbin.org",
    "port": 443,
    "protocol": "https"
  },
  "runtime": {
    "javaVersion": "21.0.1",
    "availableProcessors": 8,
    "maxMemoryMB": 512,
    "uptimeSeconds": 3600,
    "activeConnections": 10
  },
  "timestamp": 1704520800000
}
```

### Prometheus 指标

```bash
curl http://localhost:9090/metrics

# 返回 Prometheus 格式的指标数据
# HELP proxy_connections_active Number of active client connections
# TYPE proxy_connections_active gauge
proxy_connections_active 5.0
# HELP proxy_requests_total Total number of requests
# TYPE proxy_requests_total counter
proxy_requests_total 12345.0
...
```

### Kubernetes 集成

```yaml
# 健康检查配置
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

## 证书管理

### 证书文件结构

```
src/main/resources/certificates/
├── ca.crt                      # CA 根证书
├── server/
│   └── server.p12              # 服务器证书 + 私钥
└── trusted/
    ├── client.p12              # 客户端证书（PKCS12）
    ├── client-full.pem         # 客户端证书（PEM，用于 curl）
    └── truststore.p12          # 信任库（包含 CA）
```

### 证书白名单/黑名单

```yaml
ssl:
  # 白名单：只允许这些证书连接（留空则不启用）
  whitelistFingerprints:
    - "A1:B2:C3:D4:E5:F6:78:90:AB:CD:EF:12:34:56:78:90:AB:CD:EF:12:34:56:78:90:AB:CD:EF:12:34:56:78:90"

  # 黑名单：拒绝这些证书连接
  blacklistFingerprints:
    - "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88"
```

**获取证书指纹：**
```bash
openssl x509 -in client.crt -noout -fingerprint -sha256
```

### 生成生产证书

```bash
# 1. 生成 CA
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt

# 2. 生成服务器证书
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -out server.crt

# 3. 转换为 PKCS12
openssl pkcs12 -export -in server.crt -inkey server.key -out server.p12

# 4. 创建信任库
keytool -import -trustcacerts -alias ca -file ca.crt \
        -keystore truststore.p12 -storetype PKCS12
```

## 测试

### 自动化测试

```bash
# 运行所有测试
./gradlew test

# 运行特定测试
./gradlew test --tests SimpleClientTest
```

### 手动测试

```powershell
# 使用 curl 测试
curl.exe --cert certificates/trusted/client-full.pem \
         --cacert certificates/ca.crt \
         -v https://localhost:18443
```

### 测试场景矩阵

| 测试 | 证书状态 | REQUIRED | OPTIONAL | NONE |
|-----|---------|----------|----------|------|
| 受信任证书 | 有效 | 成功 | 成功 | 成功 |
| 无证书 | 无 | 拒绝 | 成功 | 成功 |
| 不受信任证书 | 无效 | 拒绝 | 拒绝 | 成功 |

## 常见问题

### Q1: Empty client certificate chain

**原因：** 服务器要求证书（`clientAuth: REQUIRED`），但客户端未提供

**解决：**
```bash
# 提供客户端证书
curl --cert client.p12:password ...

# 或修改配置为 OPTIONAL/NONE
```

### Q2: PKIX path building failed

**原因：** 客户端不信任服务器证书（未加载 CA）

**解决：**
```bash
# 指定 CA 证书
curl --cacert ca.crt ...
```

### Q3: 如何启用 DEBUG 日志？

编辑 `src/main/resources/logback.xml`：

```xml
<root level="DEBUG">
    <appender-ref ref="CONSOLE"/>
</root>
```

### Q4: Docker 容器无法启动

检查：
1. 环境变量是否设置：`KEYSTORE_PASSWORD`, `TRUSTSTORE_PASSWORD`
2. 证书文件是否正确挂载到 `/app/certs`
3. 配置文件是否正确挂载到 `/app/config`

```bash
# 查看日志
docker logs jsslproxy
```

### Q5: 指标端点返回 "Metrics disabled"

确保配置中启用了指标：

```yaml
metrics:
  enabled: true
```

## 项目结构

```
JSSLProxy/
├── src/main/
│   ├── java/com/sslproxy/
│   │   ├── config/           # 配置管理
│   │   │   ├── ProxyConfig.java
│   │   │   └── ConfigWatcher.java    # 配置热加载
│   │   ├── server/           # 服务器核心
│   │   │   ├── ProxyServer.java
│   │   │   └── ServerInitializer.java
│   │   ├── handler/          # 请求处理器
│   │   │   ├── ProxyFrontendHandler.java
│   │   │   ├── ProxyBackendHandler.java
│   │   │   └── RequestIdHandler.java
│   │   ├── client/           # 后端连接
│   │   │   ├── BackendConnector.java
│   │   │   └── BackendConnectionPool.java
│   │   ├── security/         # 证书验证
│   │   │   └── ClientAuthenticator.java
│   │   ├── management/       # 管理端点
│   │   │   └── handler/
│   │   │       ├── HealthHandler.java
│   │   │       └── MetricsHandler.java
│   │   ├── metrics/          # 指标收集
│   │   │   ├── ProxyMetrics.java
│   │   │   └── MetricsRegistry.java
│   │   ├── ratelimit/        # 限流
│   │   └── loadbalance/      # 负载均衡（可选）
│   └── resources/
│       ├── application.yml   # 主配置文件
│       ├── logback.xml       # 日志配置
│       └── certificates/     # 证书目录
├── config/
│   └── prometheus.yml        # Prometheus 配置
├── scripts/
│   ├── generate-certs.ps1    # 证书生成（Windows）
│   └── generate-certs.sh     # 证书生成（Linux/Mac）
├── Dockerfile
├── docker-compose.yml
└── build.gradle.kts
```

## 架构概览

```
┌─────────────┐         ┌──────────────────┐         ┌─────────────┐
│   客户端     │  mTLS   │  SSL Proxy       │  HTTP/  │  后端服务器  │
│             ├────────►│                  ├────────►│             │
│  (需要证书)  │  18443  │  Netty Based     │  HTTPS  │ httpbin.org │
└─────────────┘         └──────────────────┘         └─────────────┘
                               │
                               │ :9090
                               ▼
                        ┌──────────────┐
                        │  Management  │
                        │  /health     │
                        │  /ready      │
                        │  /metrics    │
                        │  /info       │
                        └──────────────┘
                               │
                               ▼
                        ┌──────────────┐
                        │  Prometheus  │
                        │  + Grafana   │
                        └──────────────┘
```

## 变更日志

### v1.0.0

- 初始版本发布
- 支持 mTLS 双向认证
- 证书白名单/黑名单管理
- 基于 Netty 的高性能架构
- Prometheus 指标集成
- Docker 容器化支持
- 配置热加载
- 后端连接池
- 请求限流

## 许可证

本项目采用 [MIT License](LICENSE)

## 致谢

- [Netty](https://netty.io/) - 高性能网络框架
- [Micrometer](https://micrometer.io/) - 指标收集
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) - YAML 解析
- [Logback](http://logback.qos.ch/) - 日志框架
