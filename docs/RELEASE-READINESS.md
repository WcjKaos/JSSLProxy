# Release Readiness

> Date: 2026-06-09
> Scope: 1.4.0 quality hardening plus lightweight Admin UI/CLI.
> Decision: conditionally releasable after the first GitHub Actions run is green on the remote branch.

## Summary

The quality pass is complete for local evidence: core tests, build, Docker image build, Docker smoke test, Windows PowerShell smoke wrapper, JMH baseline, k6 quick baseline, admin endpoint verification, lightweight Admin UI, CLI checks, and documentation are in place. No scope-expanding gateway features were added.

This is a conditional release decision because the workflow file has been created and locally validated, but GitHub Actions itself cannot be proven from this local environment until the branch is pushed.

## Verification Results

| Gate | Command | Result |
|------|---------|--------|
| Workflow YAML parse | `docker run --rm -v "${PWD}:/work" mikefarah/yq:4.44.3 '.name' /work/.github/workflows/ci.yml` | Passed, output `CI` |
| Production compose config | `docker compose config` | Passed |
| Test compose config | `docker compose -f docker-compose.test.yml config` | Passed |
| Docker full tests | `docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew test --rerun-tasks` | Passed |
| Docker build gate | `docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew build` | Passed |
| Docker image build | `docker build -t jsslproxy:release-check .` | Passed |
| Docker smoke test | `docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh` | Passed |
| Windows smoke wrapper | `powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1` | Passed |
| Admin UI targeted tests | `./gradlew test --tests "*AdminUiHandlerTest" --tests "*ManagementAuthHandlerTest"` | Passed |
| CLI targeted tests | `./gradlew test --tests "*AdminCliTest"` | Passed |
| CLI help | `java -jar build/libs/JSSLProxy-1.0-SNAPSHOT.jar ctl --help` | Passed |
| UI JavaScript syntax | `node --check src/main/resources/admin-ui/app.js` | Passed |
| JMH baseline | `docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew jmh --rerun-tasks` | Passed |
| k6 quick baseline | `bash scripts/perf-baseline.sh` | Passed |

Additional admin API verification: test compose confirmed `GET /admin/info` returns `configHash` with `Authorization: Bearer smoke-token`, returns `401` without a token, and `/admin/ui` serves only the static UI shell. Docker smoke also covers CLI `status`, `certs`, `rejected-certs`, `blacklist add/remove`, `reload certs`, `drain start/cancel`, and `metrics`.

## Performance Snapshot

JMH results were written to `build/reports/jmh/results.json`. k6 results were written to `build/reports/k6/baseline-results.json`.

| k6 metric | Value |
|-----------|------:|
| Total requests | 960 |
| Request rate | 43.30 req/s |
| Average latency | 2.63 ms |
| P95 latency | 2.61 ms |
| P99 latency | 6.59 ms |
| HTTP failure rate | 0.00% |
| Custom success rate | 100.00% |

These are Docker regression numbers, not a production SLA.

## Documentation Inventory

Required user and operator documentation is present:

- `README.md`
- `README_EN.md`
- `docs/DEPLOYMENT.md`
- `docs/ADMIN-API.md`
- `docs/ADMIN-UI.md`
- `docs/CLI.md`
- `docs/METRICS.md`
- `docs/TROUBLESHOOTING.md`
- `docs/TEST-ENVIRONMENT.md`
- `docs/TEST-MATRIX.md`
- `docs/PERFORMANCE-BASELINE.md`
- `docs/RELEASE-CHECKLIST.md`
- `docs/RELEASE-READINESS.md`
- `docs/SECURITY-AUDIT.md`
- `docs/QUALITY-GAP-REPORT.md`

## Residual Risks

| Risk | Status | Owner | Plan |
|------|--------|-------|------|
| Remote GitHub Actions has not run in this local session | Release condition | Maintainer | Push branch and require first CI run to pass before tagging |
| Host JDK 25 cannot run the current Gradle/Kotlin setup | Accepted | Maintainer | Use Java 17 or Docker JDK 17; revisit Gradle compatibility after 1.3.0 |
| Dependency vulnerability/license scanning is manual | Accepted | Maintainer | Follow `docs/RELEASE-CHECKLIST.md`; add an automated scanner later only if it does not block current stabilization |
| Gradle reports deprecated features for future Gradle 9 compatibility | Accepted | Maintainer | Track as future build-maintenance work, not a 1.3.0 release blocker |
| k6 warns that `tlsAuth.domains` may be removed in a future release | Accepted | Maintainer | Revisit when upgrading k6; current quick baseline passes |

P0 release blockers: 0. P1 release blockers: 0. All remaining items are accepted residuals with an owner and plan.

## Release Steps

1. Push the branch and confirm GitHub Actions `CI` passes.
2. Review `git status --short`; stage only expected source, docs, CI, and script changes.
3. Decide the release version. Current Gradle version remains `1.0-SNAPSHOT` until explicitly changed.
4. Build final artifacts with `./gradlew build` or the Docker equivalent.
5. Build and tag the Docker image.
6. Publish release notes summarizing 1.4.0 as quality/usability hardening with lightweight Admin UI/CLI and no gateway feature expansion.
7. Do not publish generated certificates, logs, `build/`, or local runtime data.
