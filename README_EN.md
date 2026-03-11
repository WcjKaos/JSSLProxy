# Java SSL Proxy

> A high-performance SSL/TLS proxy server built on Netty, with mTLS mutual authentication and certificate whitelist/blacklist management.

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://www.oracle.com/java/)
[![Netty](https://img.shields.io/badge/Netty-4.1+-green.svg)](https://netty.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**[中文文档](README.md)**

## Features

- **Mutual TLS (mTLS)** - Client and server mutual certificate verification
- **Certificate Whitelist/Blacklist** - Fine-grained client access control
- **High Performance** - Netty-based asynchronous event-driven architecture
- **Prometheus Metrics** - Full observability support
- **Hot Reload** - Update rate-limit parameters and certificate fingerprints without restart
- **Connection Pooling** - Efficient backend connection reuse
- **Rate Limiting** - Token-bucket based global and per-client rate limiting
- **Circuit Breaker** - Resilience4j-based backend failure protection
- **Load Balancing** - Multi-backend support with health checks
- **Docker Support** - Out-of-the-box containerized deployment

## Quick Start

### 1. Clone

```bash
git clone https://github.com/WcjKaos/JSSLProxy.git
cd JSSLProxy
```

### 2. Generate Test Certificates

```powershell
# Windows
.\scripts\generate-certs.ps1

# Linux/Mac
./scripts/generate-certs.sh
```

### 3. Set Environment Variables

```bash
# Linux/Mac
export KEYSTORE_PASSWORD=changeit
export TRUSTSTORE_PASSWORD=changeit

# Windows PowerShell
$env:KEYSTORE_PASSWORD="changeit"
$env:TRUSTSTORE_PASSWORD="changeit"
```

### 4. Start the Server

```bash
./gradlew run
```

### 5. Test Connection

```bash
curl --cert src/main/resources/certificates/trusted/client-full.pem \
     --cacert src/main/resources/certificates/ca.crt \
     https://localhost:18443
```

## Docker Deployment

```bash
# 1. Prepare config and certs
mkdir -p config certs
cp src/main/resources/application.yml config/
cp -r src/main/resources/certificates/* certs/

# 2. Set passwords
export KEYSTORE_PASSWORD=your_secure_password
export TRUSTSTORE_PASSWORD=your_secure_password

# 3. Start
docker-compose up -d

# 4. (Optional) Start with Prometheus + Grafana
docker-compose --profile monitoring up -d
```

| Service | Port | Description |
|---------|------|-------------|
| sslproxy | 8443 | SSL proxy port |
| sslproxy | 9090 | Management port (metrics/health) |
| prometheus | 9091 | Prometheus UI (monitoring profile) |
| grafana | 3000 | Grafana dashboard (monitoring profile) |

## Architecture

```
┌─────────┐        ┌──────────────────┐        ┌─────────────┐
│ Client  │  mTLS  │   SSL Proxy      │ HTTP/  │   Backend   │
│         ├───────►│   (Netty-based)  ├───────►│   Server    │
│  (cert) │ :18443 │                  │ HTTPS  │             │
└─────────┘        └──────────────────┘        └─────────────┘
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
```

### Netty Pipeline

```
MaxConnectionsHandler → IdleStateHandler → SslHandler → SSLAuthHandler
→ HttpServerCodec → HttpObjectAggregator → RequestIdHandler
→ MetricsCollectorHandler → RateLimitHandler → AccessLogHandler
→ ProxyFrontendHandler
```

## Configuration

Main config file: `src/main/resources/application.yml`

### Key Settings

```yaml
server:
  host: 0.0.0.0
  port: 18443

target:
  host: httpbin.org
  port: 443
  protocol: https

ssl:
  clientAuth: REQUIRED  # REQUIRED | OPTIONAL | NONE
  protocols: [TLSv1.3, TLSv1.2]
  handshakeTimeout: 10000

performance:
  backlog: 1024
  workerThreads: 0       # 0 = CPU cores * 2
  maxRequestSize: 10485760
  backendPool:
    enabled: true
    maxConnections: 100

rateLimit:
  enabled: false
  globalRps: 5000
  clientRps: 200
```

### Client Auth Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| **REQUIRED** | Client certificate mandatory | High-security internal systems |
| **OPTIONAL** | Client certificate optional | Mixed environments |
| **NONE** | No client certificate needed | Public-facing services |

### Hot Reload

The following can be updated without restart:
- `rateLimit.*` - Rate limit parameters
- `ssl.whitelistFingerprints` - Certificate fingerprint whitelist
- `ssl.blacklistFingerprints` - Certificate fingerprint blacklist

Manual reload via management API:
```bash
curl -X POST -H "Authorization: Bearer <token>" http://localhost:9090/admin/reload?target=config
curl -X POST -H "Authorization: Bearer <token>" http://localhost:9090/admin/reload?target=certs
```

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `KEYSTORE_PASSWORD` | Keystore password | Yes |
| `TRUSTSTORE_PASSWORD` | Truststore password | Yes |
| `CONFIG_PATH` | Config file path (Docker) | No |
| `JVM_MEMORY_OPTS` | JVM memory options (Docker) | No |

## Management Endpoints

Default port: `9090`

```bash
# Liveness check
curl http://localhost:9090/health

# Readiness check (probes backend connectivity)
curl http://localhost:9090/ready

# Service info (version, config hash, runtime stats)
curl http://localhost:9090/info

# Prometheus metrics
curl http://localhost:9090/metrics
```

### Admin API (token required)

```bash
TOKEN="your-auth-token"

# Certificate introspection
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/certs

# Blacklist management
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/admin/blacklist?fingerprint=<sha256-hex>"
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/admin/blacklist?fingerprint=<sha256-hex>"

# Whitelist management
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/admin/whitelist?fingerprint=<sha256-hex>"

# Terminate connections by fingerprint
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/admin/connections?fingerprint=<sha256-hex>"

# Drain mode (graceful shutdown)
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/admin/drain?timeout=30"
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:9090/admin/drain

# Rejected certificates (debugging mTLS issues)
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/rejected-certs

# Config/cert reload
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "http://localhost:9090/admin/reload?target=config"
```

### Kubernetes Integration

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

## Prometheus Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `proxy.connections.active` | Gauge | Active connections |
| `proxy.connections.total` | Counter | Total connections |
| `proxy.connections.errors{cause=...}` | Counter | Connection errors by cause |
| `proxy.requests.total` | Counter | Total requests |
| `proxy.requests.errors` | Counter | Request errors |
| `proxy.request.duration` | Timer | Request latency histogram |
| `proxy.ssl.handshakes.total` | Counter | SSL handshake count |
| `proxy.ssl.handshakes.failures{reason=...}` | Counter | Handshake failures by reason |
| `proxy.ssl.handshake.duration` | Timer | Handshake latency |
| `proxy.ratelimit.total` | Counter | Rate-limited requests |
| `proxy.pool.active` | Gauge | Pool active connections |
| `proxy.pool.idle` | Gauge | Pool idle connections |
| `proxy.bytes.received` | Gauge | Bytes received |
| `proxy.bytes.sent` | Gauge | Bytes sent |

## Certificate Management

### File Structure

```
src/main/resources/certificates/
├── ca.crt                      # CA root certificate
├── server/
│   └── server.p12              # Server cert + private key
└── trusted/
    ├── client.p12              # Client cert (PKCS12)
    ├── client-full.pem         # Client cert (PEM, for curl)
    └── truststore.p12          # Trust store (contains CA)
```

### Generate Production Certificates

```bash
# 1. Generate CA
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days 3650 -key ca.key -out ca.crt

# 2. Generate server certificate
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -out server.crt

# 3. Convert to PKCS12
openssl pkcs12 -export -in server.crt -inkey server.key -out server.p12

# 4. Create trust store
keytool -import -trustcacerts -alias ca -file ca.crt \
        -keystore truststore.p12 -storetype PKCS12
```

### Get Certificate Fingerprint

```bash
openssl x509 -in client.crt -noout -fingerprint -sha256
```

## Testing

```bash
# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests ProxyConfigTest

# Run JMH benchmarks
./gradlew jmh
```

### Test Matrix

| Test | Certificate | REQUIRED | OPTIONAL | NONE |
|------|------------|----------|----------|------|
| Trusted cert | Valid | Pass | Pass | Pass |
| No cert | None | Reject | Pass | Pass |
| Untrusted cert | Invalid | Reject | Reject | Pass |

## Project Structure

```
JSSLProxy/
├── src/main/java/com/sslproxy/
│   ├── config/           # Configuration management
│   ├── server/           # Server core (ProxyServer, ServerInitializer)
│   ├── handler/          # Netty handlers (Frontend, Backend, Auth, RateLimit)
│   ├── client/           # Backend connections (Connector, Pool)
│   ├── security/         # Certificate validation, revocation checking
│   ├── management/       # Management endpoints (/health, /metrics, /admin)
│   ├── metrics/          # Micrometer metrics collection
│   ├── ratelimit/        # Token-bucket rate limiting
│   ├── resilience/       # Circuit breaker
│   ├── loadbalance/      # Load balancing
│   ├── audit/            # Audit logging
│   └── util/             # Utilities
├── src/test/java/        # Unit & integration tests
├── src/jmh/java/         # JMH benchmarks
├── scripts/              # Certificate generation scripts
├── Dockerfile
├── docker-compose.yml
└── build.gradle.kts
```

## FAQ

**Q: "Empty client certificate chain"**
Server requires a client cert (`clientAuth: REQUIRED`) but none was provided. Use `--cert` with curl or switch to `OPTIONAL`/`NONE`.

**Q: "PKIX path building failed"**
Client doesn't trust the server certificate. Use `--cacert ca.crt` to specify the CA.

**Q: How to enable debug logging?**
Edit `src/main/resources/logback.xml` and set `<root level="DEBUG">`.

## License

[MIT License](LICENSE)

## Acknowledgements

- [Netty](https://netty.io/) - High-performance networking framework
- [Micrometer](https://micrometer.io/) - Metrics collection
- [Resilience4j](https://resilience4j.readme.io/) - Circuit breaker
- [Bucket4j](https://bucket4j.com/) - Rate limiting
- [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) - YAML parsing
- [Logback](http://logback.qos.ch/) - Logging framework
