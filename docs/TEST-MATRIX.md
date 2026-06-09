# Test Matrix

> Date: 2026-06-09
> Scope: T-24, core capability coverage for the 1.3.0 quality pass.

## Coverage Levels

- `E2E`: starts proxy and backend, exercises real HTTP/TLS path.
- `Component`: validates a production class with mocks or embedded Netty channel.
- `Utility`: validates pure parsing, classification, or helper behavior.
- `Deferred`: accepted for 1.3.0 only with an explicit follow-up owner/task.

## P0 Core Paths

| Capability | Required Evidence | Coverage | Status |
|------------|-------------------|----------|--------|
| TLS proxy forwards GET/POST | `ProxyForwardingIT` | E2E | Covered |
| Backend status/body passthrough | `ProxyForwardingIT` | E2E | Covered |
| Backend unavailable returns proxy error | `ProxyForwardingIT.testBackendUnavailableReturnsBadGateway` | E2E | Covered |
| mTLS trusted client allowed | `MtlsIntegrationIT.should_forwardRequest_when_clientCertificateTrusted` | E2E | Covered |
| mTLS missing client cert rejected | `MtlsIntegrationIT.should_rejectRequest_when_clientCertificateMissing` | E2E | Covered |
| mTLS untrusted client cert rejected | `MtlsIntegrationIT.should_rejectRequest_when_clientCertificateUntrusted` | E2E | Covered |
| Weak RSA / weak signature rejected | `ClientAuthenticatorTest` | Component | Covered |
| Expired cert rejected | `ClientAuthenticatorTest` | Component | Covered |
| OCSP/CRL SSRF blocks internal URLs | `CertificateRevocationCheckerSecurityTest` | Component | Covered |
| Management admin token required | `ManagementAuthHandlerTest` | Component | Covered |
| Admin UI shell is public but data APIs remain protected | `AdminUiHandlerTest`, `ManagementAuthHandlerTest`, Docker smoke `/admin/ui` check | Component + smoke | Covered |
| Admin CLI calls management API safely | `AdminCliTest`, Docker smoke CLI checks | Component + smoke | Covered |
| Unsafe external management config rejected | `ManagementSecurityValidationTest` | Component | Covered |
| Admin connection termination validates fingerprint | `AdminApiConnectionTerminateTest`, `ConnectionTrackerTerminateTest` | Component | Covered |
| `/ready` reports drain/down checks | `HealthHandlerReadyTest` | Component | Covered |
| Max client connections and drain reject new connections | `MaxConnectionsHandlerTest`, `MaxConnectionsHandlerDrainTest` | Component | Covered |
| Metrics registry exposes Prometheus text | `MetricsRegistryTest`, `ProxyMetrics*` | Component | Covered |

## P1 Resilience And Operations

| Capability | Evidence | Coverage | Notes |
|------------|----------|----------|-------|
| Circuit breaker state machine | `BackendCircuitBreakerTest` | Component | Open, half-open, reset, stats covered. |
| Circuit breaker normal proxy path | `CircuitBreakerIT` | E2E | Confirms normal requests still pass with circuit breaker enabled. |
| Backend error classification | `BackendConnectionErrorClassificationTest` | Utility | Covers timeout, refused, TLS, DNS, pool exhausted. |
| Backend hostname verification hook | `BackendHostnameVerificationIT`, `TlsUtilTest` | Component + E2E smoke | Runtime mismatch test is deferred; JDK HTTPS endpoint identification is enabled in code. |
| Dynamic white/black list operations | `DynamicCertificateManagerTest`, `DynamicCertificateManagementIT` | Component + E2E smoke | Full admin API to live TLS admission is deferred to Docker smoke test. |
| Config hot reload | `ConfigWatcherTest`, `RateLimitHotReloadTest` | Component | File watch behavior and rate-limit state transfer covered. |
| Rejected certificate tracking | `RejectedCertTrackerTest` | Component | Admin endpoint display is covered by handler path, not full HTTP smoke. |
| Trace context passthrough | `TraceparentPassthroughTest` | Component | Header preservation covered. |
| Load balancing | `LoadBalancerTest` | Component | Strategy behavior covered outside proxy E2E. |

## Deferred Items

These are not product blockers for 1.3.0, but should be exercised by T-25 Docker smoke tests or T-30 release readiness:

| Scenario | Reason | Follow-up |
|----------|--------|-----------|
| Full admin API modifies live whitelist/blacklist and immediately affects real mTLS handshakes | Requires proxy, management server, generated certs, authenticated admin calls, and multiple clients in one scenario. | T-25 Docker smoke test |
| Circuit breaker opens and recovers through the real proxy after repeated backend failures | Component state machine is covered; E2E failure sequencing is timing-sensitive and better owned by a focused integration or smoke test. | T-25/T-30 |
| Backend HTTPS hostname mismatch fails at runtime | Requires a controllable HTTPS backend cert mismatch and target hostname variation. | T-25 or future targeted IT |
| Docker image, shell smoke, k6 baseline, and Prometheus scrape | Requires `docker-compose.test.yml` test-runner infrastructure. | T-25/T-26/T-29 |

## Verification Commands

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "com.sslproxy.integration.MtlsIntegrationIT"
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "com.sslproxy.integration.ProxyForwardingIT"
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --rerun-tasks
```
