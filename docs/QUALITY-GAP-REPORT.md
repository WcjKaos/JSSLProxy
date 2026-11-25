# JSSLProxy Quality Gap Report

> Date: 2026-06-09
> Scope: 1.3.0 quality and usability hardening, with no feature expansion.
> Status: Initial P0 proxy regression and T-23 through T-30 are complete locally; remote GitHub Actions must still be checked after push.
> Regression verification updated: 2026-11-25. The supplemental results below supersede the earlier readiness and circuit-breaker coverage claims.

## Executive Summary

JSSLProxy is now passing the core Docker JDK 17 test and build gates after fixing the Netty transport mismatch that caused proxy integration tests to return `502 Bad Gateway`. T-23 through T-30 are complete locally: security audit, test matrix, Docker smoke test, performance baseline, documentation hardening, CI workflow, release checklist, and release readiness are in place. The project is usable as a focused lightweight mTLS gateway. Release remains conditional only on the first remote GitHub Actions run passing after push.

No scope expansion is recommended. The current goal remains: make existing mTLS termination, HTTP/HTTPS forwarding, certificate admission control, revocation checks, metrics, and management operations stable, trustworthy, deployable, and easy to troubleshoot.

## 2026-11-25 Regression Corrections

Three runtime defects were reproduced and fixed:

| Defect | Corrected behavior | Regression evidence |
|--------|--------------------|---------------------|
| Readiness used the initial healthy flag when periodic health checks were disabled | Parallel TCP probes with a 2-second deadline supply a fresh readiness snapshot; no reachable backends returns 503, partial availability returns 200 with `PARTIAL`. Routing health flags remain independent. | `ReadinessIT`, `BackendReadinessProbeIT`, `HealthHandlerReadyTest` |
| HTTPS health checks sent plaintext HTTP and missed some terminal failures | Probes use the configured backend trust strategy, key material and hostname validation. TLS failures, invalid responses, early EOF and deadlines finish each probe once. | `HttpsHealthCheckIT`, `BackendReadinessProbeIT` |
| Circuit-breaker calls completed when requests were written | Each admitted request completes only after its final backend response, using monotonic elapsed time. HTTP 5xx and transport failures count as failures; slow final responses affect the slow-call threshold. | `CircuitBreakerIT`, `CircuitBreakerDirectIT` |

The circuit-breaker regressions also cover interim 103 responses, pipelined requests, OPEN/HALF_OPEN recovery, backend EOF, response deadlines returning 504, and client cancellation without counting a backend failure. Incomplete pooled connections close before release, and late callbacks cannot complete a request twice. The same integration suite runs with pooling enabled and disabled.

Before the health implementation changed, all eight new `ReadinessIT` and `HttpsHealthCheckIT` scenarios failed. After the fixes, the focused suite passed 57 tests. The full Docker JDK 17 build passed 499 tests across 43 classes, with zero failures, errors or skipped tests.

| Gate | Command | Result |
|------|---------|--------|
| Focused regressions | `docker compose -f docker-compose.test.yml run --rm --no-deps test-runner ./gradlew --no-daemon test --tests '*ReadinessIT' --tests '*HttpsHealthCheckIT' --tests '*BackendReadinessProbeIT' --tests '*HealthHandlerReadyTest' --tests '*CircuitBreakerIT' --tests '*CircuitBreakerDirectIT' --tests '*BackendCircuitBreakerTest'` | Passed: 57 tests |
| Full tests and packaging | `docker compose -f docker-compose.test.yml run --rm --no-deps test-runner ./gradlew --no-daemon build` | Passed: 499 tests and artifact assembly |
| Docker operational smoke | `docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh` | Passed: mTLS forwarding/rejection, readiness, metrics, management auth, Admin UI shell and CLI operations |

The results in the remaining sections are the historical June 2026 baseline. These local changes still require CI verification after they are pushed.

## Environment Baseline

| Check | Result | Notes |
|------|--------|-------|
| Host Java | OpenJDK Corretto `25.0.2` | Host Gradle build fails before tests because Kotlin/Gradle DSL cannot parse Java version `25.0.2`. Use Java 17 or Docker. |
| Gradle Wrapper | Gradle `8.14` | Wrapper downloads and starts correctly in Docker JDK 17. |
| Docker | Docker `29.4.3`, Compose `v5.1.4` | Available and suitable for project-level test-runner work. |
| Host OpenSSL | Missing | Windows host `openssl` is not on PATH; Docker should cover routine certificate/smoke validation. |
| Docker JDK 17 | `gradle:8.14-jdk17` | Current deterministic baseline runner for `test` and `build`. |

## Command Results

| Command | Environment | Result | Evidence |
|---------|-------------|--------|----------|
| `.\gradlew.bat test` | Windows host, JDK 25 | Failed before tests | `IllegalArgumentException: 25.0.2` from Gradle/Kotlin DSL setup. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test` | Docker JDK 17, initial baseline | Failed | 443 tests, 17 failures; proxy integration paths returned `502`. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "com.sslproxy.integration.ProxyForwardingIT" --tests "com.sslproxy.integration.CircuitBreakerIT" --tests "com.sslproxy.integration.BackendHostnameVerificationIT" --tests "com.sslproxy.integration.DynamicCertificateManagementIT"` | Docker JDK 17, after T-22 fix | Passed | Previously failing proxy integration classes are green. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "*HandlerTest"` | Docker JDK 17, after T-22 fix | Passed | Handler-level regression suite is green. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test` | Docker JDK 17, after T-22 fix | Passed | Full unit and integration test suite is green. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew build` | Docker JDK 17, after T-22 fix | Passed | Build and test gate is green. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "*Security*" --tests "*Certificate*" --tests "*Management*"` | Docker JDK 17, after T-23 fix | Passed | Security, certificate, and management test subset is green. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "com.sslproxy.integration.MtlsIntegrationIT"` | Docker JDK 17, after T-24 fix | Passed | Trusted, missing, and untrusted client certificate mTLS paths are green. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "com.sslproxy.integration.ProxyForwardingIT"` | Docker JDK 17, after T-24 fix | Passed | Forwarding and backend-unavailable E2E paths are green. |
| `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --rerun-tasks` | Docker JDK 17, after T-24 fix | Passed | Full test suite was force-executed after a prior full green run. |
| `docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh` | Docker Compose test-runner | Passed | Generates temporary certs/config, starts proxy, verifies mTLS success/reject, health, ready, metrics, admin 401, Admin UI shell, and CLI checks. |
| `powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1` | Windows host wrapper + Docker Compose | Passed | Confirms the Windows smoke entrypoint works without host OpenSSL. |
| `docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew jmh` | Docker Compose test-runner | Passed | JMH baseline written to `build/reports/jmh/results.json`. |
| `bash scripts/perf-baseline.sh` | Docker Compose proxy + k6 | Passed | k6 quick baseline: 895 requests, 40.55 req/s, 0% HTTP failures. |
| `docker run --rm -v "${PWD}:/work" mikefarah/yq:4.44.3 '.name' /work/.github/workflows/ci.yml` | Docker yq | Passed | Workflow YAML parses and returns `CI`. |
| `docker compose config` | Docker Compose | Passed | Production compose config parses. |
| `docker build -t jsslproxy:release-check .` | Docker | Passed | Docker image builds with exposed proxy port `18443`. |
| `docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew test --rerun-tasks` | Docker Compose test-runner | Passed | Final forced full test gate. |
| `docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew build` | Docker Compose test-runner | Passed | Final build gate. |
| `docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew jmh --rerun-tasks` | Docker Compose test-runner | Passed | Final forced JMH gate. |
| `bash scripts/perf-baseline.sh` | Docker Compose proxy + k6 | Passed | Final k6 quick baseline: 960 requests, 43.30 req/s, avg 2.63 ms, p95 2.61 ms, p99 6.59 ms, 0% HTTP failures. |

## Closed P0 Finding

### P0-1: Basic Proxy Integration Tests Returned 502

Impact: The primary product promise, forwarding mTLS-authenticated requests to the backend, was not proven.

Root cause: The NIO server path created a `MultiThreadIoEventLoopGroup` with `NioIoHandler`, while backend client bootstraps still used `NioSocketChannel` on the inbound channel event loop. Netty rejected the incompatible pairing with `incompatible event loop type: io.netty.channel.SingleThreadIoEventLoop`.

Fix:

- `TransportFactory` now returns `NioEventLoopGroup` for the NIO transport path.
- `BackendConnector` and `BackendConnectionPool` now use `TransportFactory.getSocketChannelClass()` instead of hard-coded `NioSocketChannel`.
- `IntegrationTestBase` now waits on `proxyServer.isRunning()` with Awaitility instead of probing the TLS port with a raw TCP socket, avoiding false handshake-failure noise during startup.

Verification: The targeted failing integration classes, handler tests, full `test`, and full `build` all pass in Docker JDK 17.

## Accepted Residual Findings

### R-1: Host JDK 25 Is Not a Supported Build Runtime

Developers using the current host JDK 25 cannot run `.\gradlew.bat test`. For now, document Java 17 as the supported local runtime and use Docker JDK 17 for deterministic verification. A Gradle/Kotlin compatibility upgrade can be considered later only if it does not distract from 1.3.0 hardening.

Owner: maintainer. Plan: keep Java 17/Docker as the supported path for 1.3.0; revisit Gradle/JDK compatibility later.

### R-2: Remote GitHub Actions Result Is External

The workflow file exists and local equivalent gates pass, but the actual GitHub Actions result can only be checked after pushing the branch.

Owner: maintainer. Plan: require first remote `CI` run to pass before tagging.

## Closed P1 Finding

### P1-4: Security Audit Required Before Release

T-23 reviewed configuration validation, client certificate verification, OCSP/CRL SSRF controls, management authentication, admin API error responses, and sensitive data handling. The applied fix prevents admin certificate reload failures from returning raw exception messages to HTTP clients.

Evidence:

- `docs/SECURITY-AUDIT.md`
- `AdminApiHandlerSecurityTest`
- `ManagementAuthHandlerTest`
- Docker JDK 17 security subset test command listed above

### P1-5: Docker Smoke Test Required Before Release

T-25 added a reproducible Docker smoke test with a `test-runner` service and nginx backend. It validates the operational path most likely to catch packaging/config mistakes: temporary cert generation, proxy startup, mTLS success and rejection, management health/readiness, metrics, and admin auth rejection.

Evidence:

- `docker-compose.test.yml`
- `scripts/smoke-test.sh`
- `scripts/smoke-test.ps1`
- `docs/TEST-ENVIRONMENT.md`

### P1-6: Performance Baseline Required Before Release

T-26 established a Docker-only regression baseline. JMH covers certificate utility, circuit breaker, and rate limiter microbenchmarks. k6 quick baseline runs through the proxy with mTLS against a Compose nginx backend.

Evidence:

- `docs/PERFORMANCE-BASELINE.md`
- `scripts/perf-baseline.sh`
- `scripts/k6/baseline-test.js`
- `build/reports/jmh/results.json`
- `build/reports/k6/baseline-results.json`

## Current Coverage Snapshot

| Capability | Existing Evidence | Status |
|------------|-------------------|--------|
| Config validation | `ProxyConfigTest`, `ManagementSecurityValidationTest` | Green |
| SSL context construction | `SSLContextFactoryTest`, `SslContextHolderTest` | Green |
| Certificate utilities | `CertificateUtilTest`, `ClientAuthenticatorTest` | Green |
| OCSP/CRL SSRF safety | `CertificateRevocationCheckerSecurityTest` | Green |
| Dynamic black/white lists | `DynamicCertificateManagerTest`, `DynamicCertificateManagementIT` | Green |
| mTLS admission | `MtlsIntegrationIT` | Green for trusted, missing, and untrusted client certs |
| Metrics primitives | `ProxyMetrics*`, `MetricsRegistryTest`, `ConnectionTracker*` | Green |
| Rate limiting | `RateLimiterManagerTest`, `RateLimitHotReloadTest` | Green |
| Circuit breaker | `BackendCircuitBreakerTest`, `CircuitBreakerIT` | Green |
| Basic proxy forwarding | `ProxyForwardingIT` | Green |
| Backend unavailable through proxy | `ProxyForwardingIT.testBackendUnavailableReturnsBadGateway` | Green |
| Backend hostname verification | `BackendHostnameVerificationIT` | Green |
| Trace context passthrough | `TraceparentPassthroughTest` | Green |
| Drain and max connections | `MaxConnectionsHandler*` | Handler-level green; matrix should confirm integration depth. |
| Management auth and health | `ManagementAuthHandlerTest`, `HealthHandlerReadyTest` | Green |
| Connection termination API | `AdminApiConnectionTerminateTest`, `ConnectionTrackerTerminateTest` | Component-level green; matrix should confirm end-to-end depth. |

## Recommended Execution Order

1. Push the branch and confirm remote GitHub Actions `CI` passes.
2. Review and stage only expected changes.
3. Follow `docs/RELEASE-CHECKLIST.md` before tagging.

## Explicit Non-Goals

The following remain out of scope for 1.3.0: SNI multi-domain routing, full HTTP/2 proxying, L4/TCP transparent proxying, dynamic routing rules, distributed rate limiting, complex RBAC, full OpenTelemetry SDK integration, and Vault/ACME certificate lifecycle automation.
