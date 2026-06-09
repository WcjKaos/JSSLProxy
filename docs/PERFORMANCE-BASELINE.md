# Performance Baseline

> Date: 2026-06-09
> Scope: T-26, reproducible Docker baseline for regression comparison.
> Important: These numbers are not a production SLA.

## Environment

| Item | Value |
|------|-------|
| Host OS | Windows workspace, Docker-backed execution |
| Docker | `29.4.3` |
| Docker Compose | `5.1.4` |
| JMH runner | `gradle:8.14-jdk17` |
| JMH JVM | OpenJDK `17.0.19` |
| k6 runner | `grafana/k6:0.54.0` |
| Proxy target | nginx `backend:80` inside `docker-compose.test.yml` |
| Proxy TLS | mTLS enabled with temporary smoke-test CA/client cert |

## Commands

```powershell
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew jmh --rerun-tasks
bash scripts/perf-baseline.sh
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test
```

Result files are generated under ignored build output:

- `build/reports/jmh/results.json`
- `build/reports/k6/baseline-results.json`

## JMH Summary

JMH config: throughput mode, 2 warmup iterations, 3 measurement iterations, 1 fork.

| Benchmark | Score | Unit |
|-----------|------:|------|
| `CertificateUtilBenchmark.testFingerprintComparison` | 1711.194 | ops/us |
| `CertificateUtilBenchmark.testFingerprintFormatting` | 0.194 | ops/us |
| `CertificateUtilBenchmark.testFullFingerprintCalculation` | 0.206 | ops/us |
| `CertificateUtilBenchmark.testSha1Hash` | 1.079 | ops/us |
| `CertificateUtilBenchmark.testSha256Hash` | 1.938 | ops/us |
| `CertificateUtilBenchmark.testSha256HashReused` | 1.996 | ops/us |
| `CircuitBreakerBenchmark.testAllowRequest` | 1267.840 | ops/us |
| `CircuitBreakerBenchmark.testFullRequestCycle` | 14.526 | ops/us |
| `CircuitBreakerBenchmark.testGetStats` | 61.595 | ops/us |
| `CircuitBreakerBenchmark.testMixedScenario` | 5.155 | ops/us |
| `CircuitBreakerBenchmark.testRecordFailure` | 1.917 | ops/us |
| `CircuitBreakerBenchmark.testRecordSuccess` | 14.879 | ops/us |
| `RateLimiterBenchmark.testClientRateLimitCheck` | 17.050 | ops/us |
| `RateLimiterBenchmark.testFullRateLimitCheck` | 36.530 | ops/us |
| `RateLimiterBenchmark.testGlobalRateLimitCheck` | 39.888 | ops/us |
| `RateLimiterBenchmark.testHighConcurrencyRateLimit` | 109.162 | ops/us |
| `RateLimiterBenchmark.testMultiClientRateLimitCheck` | 40.544 | ops/us |

## k6 Quick Baseline

Command: `bash scripts/perf-baseline.sh`

Profile: 5 VUs for 20 seconds through the proxy at `https://proxy:18443/`, with mTLS client certs.

| Metric | Value |
|--------|------:|
| Total requests | 960 |
| Request rate | 43.30 req/s |
| Average latency | 2.63 ms |
| P95 latency | 2.61 ms |
| P99 latency | 6.59 ms |
| HTTP failure rate | 0.00% |
| Custom success rate | 100.00% |

## Regression Rules

Use this baseline for relative comparison only. A future performance-sensitive change should explain or revert if, under the same Docker setup:

- k6 request rate drops by more than 15%.
- k6 P95 latency increases by more than 25%.
- k6 HTTP failure rate rises above 1%.
- JMH scores for directly affected components regress by more than 20%.

For release claims or capacity planning, rerun k6 and JMH on the target host or production-like hardware and record those results separately.
