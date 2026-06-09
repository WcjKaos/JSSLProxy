# Release Checklist

> Scope: release gates for JSSLProxy 1.4.0 quality hardening plus lightweight Admin UI/CLI. Long soak and production capacity tests are manual; CI should stay fast enough for PR feedback.

## Required PR Gates

The GitHub Actions workflow in `.github/workflows/ci.yml` must pass on pull requests and pushes to `main` or `master`:

- Java 17 setup with Gradle cache.
- `./gradlew --no-daemon test`.
- `./gradlew --no-daemon build`.
- `docker compose -f docker-compose.test.yml config`.
- `docker build -t jsslproxy:ci .`.
- `docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh`, including Admin UI and CLI smoke checks.

CI uses the Gradle wrapper and does not require committed certificates or real secrets. The smoke test generates temporary certificates under ignored build output.

## Required Local Release Gates

Run these before tagging a release:

```bash
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew test --rerun-tasks
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew build
docker build -t jsslproxy:release-check .
docker compose -f docker-compose.test.yml run --rm test-runner bash ./scripts/smoke-test.sh
docker compose -f docker-compose.test.yml run --rm test-runner ./gradlew jmh
bash scripts/perf-baseline.sh
docker compose -f docker-compose.test.yml down --remove-orphans
```

Windows PowerShell wrapper:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-test.ps1
```

## Manual Dependency And License Review

No automated dependency scanner is introduced for 1.3.0. Before release:

- Review `build.gradle.kts` dependency changes.
- Run `./gradlew dependencies` or the Docker equivalent to inspect resolved versions.
- Check newly added dependencies for known CVEs, compatible licenses, and active maintenance.
- Review Docker base images: `gradle:8.14-jdk17`, `eclipse-temurin:17-jre-alpine`, `nginx:alpine`, and `grafana/k6:0.54.0`.
- Record accepted dependency risks in `docs/RELEASE-READINESS.md`.

## Artifact And Documentation Gates

- Version and tag are decided before release; current Gradle version is `1.0-SNAPSHOT` until explicitly changed.
- Fat JAR exists under `build/libs/JSSLProxy-1.0-SNAPSHOT.jar`.
- Docker image builds locally.
- README, README_EN, deployment guide, Admin API guide, Admin UI guide, CLI guide, metrics guide, troubleshooting guide, test environment strategy, test matrix, performance baseline, and release readiness are current.
- CLI help and token redaction checks pass: `java -jar build/libs/JSSLProxy-1.0-SNAPSHOT.jar ctl --help`.
- Admin UI static shell loads at `/admin/ui`, while `/admin/info` and other JSON admin endpoints still reject missing tokens.
- No generated certificates, logs, build output, or secrets are staged.

## Long Checks

Run longer stress, spike, soak, and target-hardware performance tests only when the release changes request handling, TLS, connection pooling, rate limiting, circuit breaking, or Netty transport behavior. Record the command, environment, and result separately from the Docker regression baseline.
