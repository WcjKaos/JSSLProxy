# Repository Guidelines

## Product Goal

JSSLProxy is a lightweight mTLS security gateway and reverse proxy, built on Netty. Its final product direction is TLS termination, HTTP/HTTPS forwarding, client certificate admission control, revocation checks, observability, and production operations in a single-JAR deployment. Keep the scope narrow: do not turn this into a general API gateway, service mesh, dynamic routing engine, or Envoy replacement unless the user explicitly changes the roadmap.

## Project Structure & Module Organization

Main code lives in `src/main/java/com/sslproxy`, grouped by responsibility: `server`, `handler`, `client`, `config`, `security`, `ratelimit`, `resilience`, `loadbalance`, `metrics`, `management`, and `audit`. Runtime resources are in `src/main/resources`; `application.yml` is the default config. Tests live in `src/test/java/com/sslproxy`; integration tests use the `*IT` suffix. JMH benchmarks are in `src/jmh/java`. Operational files include `Dockerfile`, `docker-compose.yml`, `config/prometheus.yml`, certificate scripts in `scripts/`, and k6 load tests in `scripts/k6/`.

## Build, Test, and Development Commands

Use the Gradle wrapper. On Windows, replace `./gradlew` with `.\gradlew.bat`. For routine verification, prefer Docker JDK 17 because the current host JDK 25 is not a supported build runtime for this project.

- `./gradlew build`: compile, run tests, and assemble artifacts.
- `./gradlew test`: run the JUnit 5 test suite.
- `./gradlew test --tests ProxyConfigTest`: run one test class; quote fully qualified names or methods when needed.
- `./gradlew run`: start the proxy with `com.sslproxy.ProxyApplication`.
- `./gradlew jar`: build the fat JAR with runtime dependencies.
- `./gradlew jmh`: run benchmarks and write results to `build/reports/jmh/results.json`.
- `docker-compose up -d`: run the containerized proxy; add `--profile monitoring` for Prometheus/Grafana.
- `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test`: current reproducible test baseline.
- `docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew build`: current reproducible build baseline.
- `docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh`: Docker smoke test for local/CI verification.
- `powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1`: Windows wrapper for the Docker smoke test.

Before running locally, generate test certificates with `.\scripts\generate-certs.ps1` or `./scripts/generate-certs.sh`, then set `KEYSTORE_PASSWORD` and `TRUSTSTORE_PASSWORD`.

## Development Workflow

Before any non-trivial change, answer three questions: what exact behavior changes, where it belongs, and what constraints must not be broken. Read affected files before editing. Prefer test-first changes: add or adjust focused tests, implement, then run `./gradlew test`; run `./gradlew build` before finishing broader work. Update `.dev/TODO.md` only when task tracking changes are required; it is the single task source of truth.

High-risk work requires extra review before implementation: Netty pipeline order, TLS/mTLS validation, certificate revocation, `ByteBuf` reference counting, connection pool acquire/release logic, management authentication, and security-sensitive admin APIs. Use the detailed synced rules in `.codex/guidance/`:

- `netty.md`: EventLoop, pipeline, backpressure, and `ByteBuf` rules.
- `security.md`: TLS, certificate, SSRF, management port, and secret handling rules.
- `testing.md`: unit, integration, async, and benchmark expectations.
- `workflow.md`: task flow, risk tiers, and commit format.
- `review.md`: multi-perspective review process for architecture, security, and performance changes.

## Coding Style & Naming Conventions

Follow the existing Java style: 4-space indentation, package-private tests, descriptive class names, and SLF4J logging. Keep Netty handlers non-blocking: avoid `sync()`, `Future.get()`, `Thread.sleep()`, and blocking I/O on EventLoop threads. Every `ByteBuf.retain()` needs a clear release or write path.

## Testing Guidelines

Tests use JUnit 5, Mockito, AssertJ, and Awaitility. Prefer Arrange/Act/Assert structure. Use `TestCertificates`, `TestBackendServer`, and `IntegrationTestBase` for TLS and proxy behavior. Async assertions should use Awaitility, not sleeps. Security, pipeline, certificate, rate-limit, circuit-breaker, and connection-pool changes need targeted integration coverage. Record Docker verification results in `docs/QUALITY-GAP-REPORT.md` or the relevant task note when they affect release readiness.

## Commit & Pull Request Guidelines

Git history and project workflow use Conventional Commits, for example `feat: JSSLProxy initial version` or `fix(handler): release ByteBuf on backend failure`. Useful scopes include `security`, `handler`, `config`, `pool`, `ratelimit`, `circuit`, `lb`, `mgmt`, and `metrics`.

Pull requests should include a short summary, risk notes for TLS/Netty/config changes, linked issues if any, and the commands run (`./gradlew test`, `./gradlew build`, or `./gradlew jmh` for performance work). Do not commit generated certificates, logs, build output, or local runtime data.

## Codex Project Environment

Project-level MCP servers are configured in `.codex/config.toml`, not globally: `chrome-devtools`, `playwright`, and `context7`. Use `/mcp` in the Codex TUI or `codex mcp list` from this repository to verify they are active. The old Claude guidance remains in `CLAUDE.md`; Codex should use this `AGENTS.md` plus `.codex/guidance/` as the synchronized working instructions.
