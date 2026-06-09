#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.test.yml}"
REPORT_DIR="${REPORT_DIR:-build/reports/k6}"

log() {
  printf '[perf] %s\n' "$*"
}

cleanup() {
  docker compose -f "$COMPOSE_FILE" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$REPORT_DIR"

log "generating smoke certificates and proxy config"
docker compose -f "$COMPOSE_FILE" run --rm \
  -e SERVER_HOST=0.0.0.0 \
  -e MANAGEMENT_HOST=0.0.0.0 \
  test-runner bash ./scripts/smoke-test.sh setup

log "starting backend and proxy services"
docker compose -f "$COMPOSE_FILE" up -d backend proxy

log "waiting for proxy health"
for _ in $(seq 1 90); do
  if docker compose -f "$COMPOSE_FILE" run --rm --no-deps test-runner \
    curl -fsS http://proxy:19090/health >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

docker compose -f "$COMPOSE_FILE" run --rm --no-deps test-runner \
  curl -fsS http://proxy:19090/health >/dev/null

log "running k6 quick baseline through proxy"
docker compose -f "$COMPOSE_FILE" --profile perf run --rm --no-deps \
  -e PROXY_URL=https://proxy:18443 \
  -e PROXY_PATH=/ \
  -e TLS_CERT=/workspace/build/smoke-test/certs/trusted/client.crt \
  -e TLS_KEY=/workspace/build/smoke-test/certs/trusted/client.key \
  -e TLS_DOMAIN=proxy \
  -e K6_QUICK=true \
  -e K6_SUMMARY_PATH=/workspace/build/reports/k6/baseline-results.json \
  k6 run scripts/k6/baseline-test.js

log "PASS: performance baseline completed"
