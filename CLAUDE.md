# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run the proxy server (requires KEYSTORE_PASSWORD and TRUSTSTORE_PASSWORD env vars)
./gradlew run

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests ProxyConfigTest

# Run a single test method
./gradlew test --tests "ProxyConfigTest.testLoadConfig"

# Run JMH benchmarks
./gradlew jmh

# Build fat JAR
./gradlew jar
```

## Environment Setup

```bash
# Required environment variables for running the server
export KEYSTORE_PASSWORD=changeit
export TRUSTSTORE_PASSWORD=changeit

# Generate test certificates (run once)
./scripts/generate-certs.sh      # Linux/Mac
.\scripts\generate-certs.ps1     # Windows
```

## Architecture Overview

This is a Netty-based SSL/TLS termination proxy with mTLS support.

### Request Flow

```
Client → ServerInitializer (builds pipeline) → Handler Chain → BackendConnector → Backend Server
```

**Netty Pipeline Order** (in `ServerInitializer`):
1. `MaxConnectionsHandler` - Connection limits
2. `SslHandler` - TLS termination
3. `SSLAuthHandler` - Certificate validation post-handshake
4. `HttpServerCodec` / `HttpObjectAggregator` - HTTP parsing
5. `RateLimitHandler` - Token bucket rate limiting
6. `ProxyFrontendHandler` - Core proxy logic, establishes backend connection

### Key Components

| Component | Location | Responsibility |
|-----------|----------|----------------|
| `ProxyServer` | `server/` | Lifecycle orchestration, initializes all subsystems |
| `ProxyFrontendHandler` | `handler/` | Receives requests, manages backend connection, implements backpressure |
| `ProxyBackendHandler` | `handler/` | Forwards backend responses to client |
| `BackendConnector` | `client/` | Creates backend connections |
| `BackendConnectionPool` | `client/` | Optional connection pooling |
| `LoadBalancerManager` | `loadbalance/` | Multi-backend selection + health checks |
| `BackendCircuitBreaker` | `resilience/` | Resilience4j circuit breaker wrapper |
| `RateLimiterManager` | `ratelimit/` | Bucket4j-based rate limiting |
| `SslContextHolder` | `config/` | SSL context with hot-reload support |
| `ClientAuthenticator` | `security/` | Client certificate validation |
| `ProxyMetrics` | `metrics/` | Micrometer metrics collection |

### Configuration

Main config: `src/main/resources/application.yml`

Config is loaded by `ProxyConfig` and supports hot-reload via `ConfigWatcher` for:
- Rate limit parameters
- Certificate fingerprint whitelist/blacklist

### Management Endpoints (port 9090)

- `/health` - Health check
- `/metrics` - Prometheus metrics
- `/admin/*` - Admin API (whitelist/blacklist management)

## Testing

- Unit tests: `src/test/java/com/sslproxy/`
- Integration tests: Classes ending with `IT` (e.g., `ProxyForwardingIT`)
- `IntegrationTestBase` - Base class providing test infrastructure
- `TestBackendServer` - Mock backend for integration tests
- `TestCertificates` - Certificate generation utilities

## Key Patterns

- **Handler Chain**: Netty ChannelPipeline with composable handlers
- **Circuit Breaker**: Resilience4j wrapping backend calls
- **Token Bucket**: Bucket4j for rate limiting (global + per-client)
- **Backpressure**: Auto-read control based on channel writability
- **Hot Reload**: File watchers for config and certificates

## Critical Netty Rules (summary)

- **NEVER block the EventLoop** — no sync I/O, no `Future.get()`, no locks
- **ByteBuf refcount**: `retain()` before async ops; `release()` on every failure path
- **Pipeline order** matters for security — see `ServerInitializer` comments
- Full rules: `.claude/rules/netty.md`

## Project Rules

Detailed guidelines are in `.claude/rules/`:

| File | Topic |
|------|-------|
| `netty.md` | EventLoop, ByteBuf refcount, Handler design, backpressure |
| `security.md` | TLS/mTLS rules, cert validation, SSRF, management port |
| `testing.md` | Test layers, Netty-specific patterns, coverage checklist |
| `workflow.md` | Task flow, risk tiers, commit format |
| `review.md` | Multi-agent dialectical review (when to trigger + checklist) |

## Security Status

All P0 security issues resolved. Items previously listed as "known issues" were confirmed as false positives after expert review.

## Task Management

开发任务清单统一维护在 `.dev/TODO.md`。

**规则（Claude 必须遵守）**：
- 识别出新的待办事项时，直接追加到 `.dev/TODO.md` 对应里程碑下，按 `T-XX` 续编
- 开始实现某个任务前，将其状态改为 `🔄 进行中`
- 任务完成（验收标准全部打勾）后，将状态改为 `✅ 完成`
- 任务描述或验收标准发生变化时，原地更新，不新增重复条目
- 不要在其他地方另立任务列表——`.dev/TODO.md` 是唯一来源
