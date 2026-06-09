# Java SSL Proxy

> A lightweight Netty-based mTLS security gateway for TLS termination, HTTP/HTTPS forwarding, client certificate admission control, and operational observability.

**[中文文档](README.md)**

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Netty](https://img.shields.io/badge/Netty-4.1+-green.svg)](https://netty.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## Product Scope

JSSLProxy is intentionally narrow: a single-JAR mTLS reverse proxy for Java and self-hosted infrastructure. It covers TLS termination, certificate whitelist/blacklist control, revocation checks, rate limiting, circuit breaking, Prometheus metrics, and admin operations.

It is not a general API gateway, service mesh, dynamic routing engine, full HTTP/2 proxy, L4 transparent proxy, complex RBAC system, or certificate lifecycle platform.

## 5-Minute Verification

Start with the Docker smoke test. It creates temporary files under `build/smoke-test/`, starts an nginx backend and the proxy, then verifies a trusted mTLS request, missing-certificate rejection, `/health`, `/ready`, `/metrics`, and unauthorized `/admin/*`.

```powershell
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
docker compose -f docker-compose.test.yml down --remove-orphans

# Windows PowerShell wrapper
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1
```

## Local Development

Use Java 17. The current host JDK 25 is not a supported validation runtime for this project; use Docker if your local Java version differs.

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

The default `src/main/resources/application.yml` is a development sample. It listens on proxy port `18443`, management port `9090`, and uses `ssl.clientAuth: NONE`. To test mandatory mTLS locally, change `ssl.clientAuth` to `REQUIRED`, then call:

```bash
curl --cert src/main/resources/certificates/trusted/client-full.pem \
  --cacert src/main/resources/certificates/ca.crt \
  https://localhost:18443/
```

## Docker Deployment

Production compose uses `docker-compose.yml`. The proxy is published as `18443:18443`; management is published to host loopback as `127.0.0.1:9090:9090`.

```bash
mkdir -p config certs data
cp src/main/resources/application.yml config/application.yml
cp -r src/main/resources/certificates/* certs/

# Edit config/application.yml using the checklist below before starting

export KEYSTORE_PASSWORD='replace-with-strong-password'
export TRUSTSTORE_PASSWORD='replace-with-strong-password'
export MANAGEMENT_TOKEN='replace-with-random-token'

docker compose up -d --build
```

Before production use, edit `config/application.yml`:

- Set `ssl.clientAuth: REQUIRED` unless client certificates are intentionally disabled.
- Point `ssl.keystore.path` and `ssl.truststore.path` to files under `/app/certs/...`.
- Do not use `changeit`; inject secrets through `KEYSTORE_PASSWORD` and `TRUSTSTORE_PASSWORD`.
- If management must be reachable outside the container, set `management.host: 0.0.0.0`, configure `MANAGEMENT_TOKEN`, and restrict network access.
- For dynamic list persistence, set `security.dynamicListPersistencePath: /app/data/dynamic-lists.json`.

See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for the full checklist.

## Management Endpoints

The management server listens on `9090` by default.

| Endpoint | Auth | Purpose |
|----------|------|---------|
| `GET /health` | No | Liveness check |
| `GET /ready` | No | Readiness check; returns 503 for drain, all backends down, or invalid TLS context |
| `GET /metrics` | No | Prometheus metrics |
| `GET /admin/ui` | No | Static admin UI shell; in-page data requests still require a token |
| `GET /admin/info` | Yes | Version, config hash, runtime info |
| `/admin/*` | Yes | Black/white lists, connection termination, reload, drain, cert inspection |

Browser UI:

```bash
open http://localhost:9090/admin/ui
```

The page stores the token in `sessionStorage`; runtime data still comes from protected `/admin/*` APIs. See [docs/ADMIN-UI.md](docs/ADMIN-UI.md).

Admin API examples:

```bash
TOKEN="$MANAGEMENT_TOKEN"
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/info
curl -H "Authorization: Bearer $TOKEN" http://localhost:9090/admin/certs
curl -X POST -H "Authorization: Bearer $TOKEN" --data-binary "$FINGERPRINT" \
  http://localhost:9090/admin/blacklist
```

See [docs/ADMIN-API.md](docs/ADMIN-API.md) for all endpoints.

CLI usage:

```bash
./gradlew jar
JSSLPROXY_ADMIN_URL=http://localhost:9090 \
JSSLPROXY_ADMIN_TOKEN="$MANAGEMENT_TOKEN" \
./scripts/jsslproxyctl.sh status
```

Use the CLI for SSH sessions, scripts, and CI. Write operations require `--yes`. See [docs/CLI.md](docs/CLI.md).

## Metrics And Troubleshooting

Prometheus endpoint:

```bash
curl http://localhost:9090/metrics
```

Common metrics include `proxy_requests_total`, `proxy_requests_errors_total`, `proxy_connections_active`, `proxy_connections_errors_total{cause="..."}`, `proxy_ssl_handshakes_failures_total{reason="..."}`, `proxy_request_duration_seconds_bucket`, and `proxy_ssl_cert_expiry_seconds{type="server"}`.

See [docs/METRICS.md](docs/METRICS.md) and [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Testing

Use Docker JDK 17 for routine validation:

```bash
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew test
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew build
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
docker compose -f docker-compose.test.yml down --remove-orphans
```

Performance baseline:

```bash
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew jmh
bash scripts/perf-baseline.sh
```

See [docs/TEST-ENVIRONMENT.md](docs/TEST-ENVIRONMENT.md) and [docs/TEST-MATRIX.md](docs/TEST-MATRIX.md).
Release gates are in [docs/RELEASE-CHECKLIST.md](docs/RELEASE-CHECKLIST.md), with final readiness in [docs/RELEASE-READINESS.md](docs/RELEASE-READINESS.md).

## Project Structure

```text
JSSLProxy/
├── src/main/java/com/sslproxy/
│   ├── server/        # Startup, Netty transport, context
│   ├── handler/       # TLS, auth, rate limit, access log, forwarding handlers
│   ├── client/        # Backend connection and pool
│   ├── security/      # Certificate validation, revocation, dynamic lists
│   ├── management/    # /health, /ready, /metrics, /admin/*
│   ├── metrics/       # Micrometer metrics
│   ├── ratelimit/     # Bucket4j rate limiting
│   ├── resilience/    # Circuit breaker
│   └── audit/         # Audit logging
├── src/test/java/     # JUnit 5 unit and integration tests
├── src/jmh/java/      # JMH benchmarks
├── scripts/           # Certificate, smoke, and performance scripts
├── docs/              # Deployment, API, metrics, troubleshooting, testing docs
├── Dockerfile
├── docker-compose.yml
└── docker-compose.test.yml
```

## License

[MIT License](LICENSE)
