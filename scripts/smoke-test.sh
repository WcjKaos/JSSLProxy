#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SMOKE_DIR="${SMOKE_DIR:-build/smoke-test}"
CERT_DIR="$SMOKE_DIR/certs"
PROXY_PORT="${PROXY_PORT:-18443}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-19090}"
SERVER_HOST="${SERVER_HOST:-127.0.0.1}"
MANAGEMENT_HOST="${MANAGEMENT_HOST:-127.0.0.1}"
BACKEND_HOST="${BACKEND_HOST:-backend}"
BACKEND_PORT="${BACKEND_PORT:-80}"
PASSWORD="${SMOKE_PASSWORD:-changeit}"
MANAGEMENT_TOKEN="${MANAGEMENT_TOKEN:-smoke-token}"
CONFIG_FILE="$SMOKE_DIR/application-smoke.yml"
PROXY_LOG="$SMOKE_DIR/proxy.log"
APP_JAR="build/libs/JSSLProxy-1.0-SNAPSHOT.jar"
PROXY_PID=""

log() {
  printf '[smoke] %s\n' "$*"
}

fail() {
  printf '[smoke] FAIL: %s\n' "$*" >&2
  if [ -f "$PROXY_LOG" ]; then
    printf '%s\n' '--- proxy log tail ---' >&2
    tail -n 120 "$PROXY_LOG" >&2 || true
  fi
  exit 1
}

cleanup() {
  if [ -n "${PROXY_PID:-}" ] && kill -0 "$PROXY_PID" 2>/dev/null; then
    kill "$PROXY_PID" 2>/dev/null || true
    wait "$PROXY_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

generate_certs() {
  log "generating temporary certificates"
  rm -rf "$SMOKE_DIR"
  mkdir -p "$CERT_DIR/server" "$CERT_DIR/trusted"

  openssl genrsa -out "$CERT_DIR/ca.key" 4096 >/dev/null 2>&1
  openssl req -new -x509 -days 30 \
    -key "$CERT_DIR/ca.key" \
    -out "$CERT_DIR/ca.crt" \
    -subj "/C=CN/O=JSSLProxy Smoke/CN=JSSLProxy Smoke CA" >/dev/null 2>&1

  openssl genrsa -out "$CERT_DIR/server/server.key" 2048 >/dev/null 2>&1
  openssl req -new \
    -key "$CERT_DIR/server/server.key" \
    -out "$CERT_DIR/server/server.csr" \
    -subj "/C=CN/O=JSSLProxy Smoke/CN=localhost" >/dev/null 2>&1
  cat > "$CERT_DIR/server/san.ext" <<'EOF'
subjectAltName = DNS:localhost,IP:127.0.0.1
extendedKeyUsage = serverAuth
EOF
  openssl x509 -req \
    -in "$CERT_DIR/server/server.csr" \
    -CA "$CERT_DIR/ca.crt" \
    -CAkey "$CERT_DIR/ca.key" \
    -CAcreateserial \
    -out "$CERT_DIR/server/server.crt" \
    -days 30 \
    -extfile "$CERT_DIR/server/san.ext" >/dev/null 2>&1
  openssl pkcs12 -export \
    -in "$CERT_DIR/server/server.crt" \
    -inkey "$CERT_DIR/server/server.key" \
    -out "$CERT_DIR/server/server.p12" \
    -name server \
    -CAfile "$CERT_DIR/ca.crt" \
    -caname ca \
    -chain \
    -password "pass:$PASSWORD" >/dev/null 2>&1

  openssl genrsa -out "$CERT_DIR/trusted/client.key" 2048 >/dev/null 2>&1
  openssl req -new \
    -key "$CERT_DIR/trusted/client.key" \
    -out "$CERT_DIR/trusted/client.csr" \
    -subj "/C=CN/O=JSSLProxy Smoke Client/CN=smoke-client" >/dev/null 2>&1
  cat > "$CERT_DIR/trusted/client.ext" <<'EOF'
extendedKeyUsage = clientAuth
EOF
  openssl x509 -req \
    -in "$CERT_DIR/trusted/client.csr" \
    -CA "$CERT_DIR/ca.crt" \
    -CAkey "$CERT_DIR/ca.key" \
    -CAcreateserial \
    -out "$CERT_DIR/trusted/client.crt" \
    -days 30 \
    -extfile "$CERT_DIR/trusted/client.ext" >/dev/null 2>&1
  openssl pkcs12 -export \
    -in "$CERT_DIR/trusted/client.crt" \
    -inkey "$CERT_DIR/trusted/client.key" \
    -out "$CERT_DIR/trusted/client.p12" \
    -name client \
    -CAfile "$CERT_DIR/ca.crt" \
    -caname ca \
    -chain \
    -password "pass:$PASSWORD" >/dev/null 2>&1

  rm -f "$CERT_DIR/trusted/truststore.p12"
  keytool -import -trustcacerts \
    -alias ca \
    -file "$CERT_DIR/ca.crt" \
    -keystore "$CERT_DIR/trusted/truststore.p12" \
    -storetype PKCS12 \
    -storepass "$PASSWORD" \
    -noprompt >/dev/null 2>&1
}

write_config() {
  log "writing smoke configuration"
  cat > "$CONFIG_FILE" <<EOF
server:
  host: $SERVER_HOST
  port: $PROXY_PORT

target:
  host: $BACKEND_HOST
  port: $BACKEND_PORT
  protocol: http

ssl:
  keystore:
    path: $CERT_DIR/server/server.p12
    password: $PASSWORD
    type: PKCS12
  truststore:
    path: $CERT_DIR/trusted/truststore.p12
    password: $PASSWORD
    type: PKCS12
  clientAuth: REQUIRED
  protocols: [TLSv1.3, TLSv1.2]
  backendTrustStrategy: SYSTEM
  revocation:
    enabled: false

connection:
  timeout: 5000
  keepalive: true

performance:
  backlog: 128
  maxClientConnections: 100
  backendPool:
    enabled: true
    maxConnections: 10
    acquireTimeout: 2000
    maxIdleTime: 10000

management:
  enabled: true
  host: $MANAGEMENT_HOST
  port: $MANAGEMENT_PORT
  authEnabled: true
  authToken: $MANAGEMENT_TOKEN

metrics:
  enabled: true
  includeJvmMetrics: false

rateLimit:
  enabled: false

circuitBreaker:
  enabled: false

shutdown:
  timeoutSeconds: 5
  awaitRequests: true
EOF
}

start_proxy() {
  log "starting proxy"
  java -jar "$APP_JAR" "$CONFIG_FILE" > "$PROXY_LOG" 2>&1 &
  PROXY_PID="$!"

  for _ in $(seq 1 90); do
    if curl -fsS "http://127.0.0.1:$MANAGEMENT_PORT/health" > "$SMOKE_DIR/health.json" 2>/dev/null; then
      log "health check passed"
      return
    fi
    if ! kill -0 "$PROXY_PID" 2>/dev/null; then
      fail "proxy process exited before health check passed"
    fi
    sleep 1
  done

  fail "proxy health check timed out"
}

build_artifacts() {
  log "building application jar"
  if command -v gradle >/dev/null 2>&1; then
    gradle --no-daemon jar >/dev/null
  else
    ./gradlew --no-daemon jar >/dev/null
  fi
  [ -f "$APP_JAR" ] || fail "application jar not found: $APP_JAR"
}

check_ready() {
  log "checking readiness"
  curl -fsS "http://127.0.0.1:$MANAGEMENT_PORT/ready" > "$SMOKE_DIR/ready.json" \
    || fail "/ready did not return 200"
  grep -q '"status": "UP"' "$SMOKE_DIR/ready.json" \
    || fail "/ready did not report UP"
}

check_mtls_success() {
  log "checking mTLS request"
  curl -fsS \
    --cert "$CERT_DIR/trusted/client.crt" \
    --key "$CERT_DIR/trusted/client.key" \
    --cacert "$CERT_DIR/ca.crt" \
    "https://localhost:$PROXY_PORT/" > "$SMOKE_DIR/mtls-success.html" \
    || fail "mTLS request with trusted client certificate failed"
  grep -qi "nginx" "$SMOKE_DIR/mtls-success.html" \
    || fail "backend response did not look like nginx output"
}

check_mtls_reject() {
  log "checking missing-client-certificate rejection"
  if curl -fsS \
    --cacert "$CERT_DIR/ca.crt" \
    "https://localhost:$PROXY_PORT/" > "$SMOKE_DIR/mtls-reject.out" 2> "$SMOKE_DIR/mtls-reject.err"; then
    fail "request without client certificate unexpectedly succeeded"
  fi
}

check_management_auth() {
  log "checking admin auth rejection"
  code="$(curl -sS -o "$SMOKE_DIR/admin-unauthorized.txt" -w "%{http_code}" \
    "http://127.0.0.1:$MANAGEMENT_PORT/admin/stats")"
  [ "$code" = "401" ] || fail "expected /admin/stats without token to return 401, got $code"
}

check_admin_ui() {
  log "checking admin UI shell"
  code="$(curl -sS -o "$SMOKE_DIR/admin-ui.html" -w "%{http_code}" \
    "http://127.0.0.1:$MANAGEMENT_PORT/admin/ui")"
  [ "$code" = "200" ] || fail "expected /admin/ui to return 200, got $code"
  grep -q "JSSLProxy Admin" "$SMOKE_DIR/admin-ui.html" \
    || fail "/admin/ui did not return the admin UI shell"
}

check_metrics() {
  log "checking metrics"
  curl -fsS "http://127.0.0.1:$MANAGEMENT_PORT/metrics" > "$SMOKE_DIR/metrics.txt" \
    || fail "/metrics did not return 200"
  [ -s "$SMOKE_DIR/metrics.txt" ] || fail "/metrics response is empty"
}

check_cli() {
  log "checking admin CLI"
  local fingerprint="AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899"
  local cli_output=""

  run_cli() {
    local label="$1"
    local outfile="$2"
    shift 2

    if ! cli_output="$(bash ./scripts/jsslproxyctl.sh \
      --url "http://127.0.0.1:$MANAGEMENT_PORT" \
      --token "$MANAGEMENT_TOKEN" \
      "$@")"; then
      fail "CLI $label command failed"
    fi

    printf '%s\n' "$cli_output" > "$outfile"
  }

  assert_cli_contains() {
    local expected="$1"
    local message="$2"
    [[ "$cli_output" == *"$expected"* ]] || fail "$message"
  }

  run_cli "status" "$SMOKE_DIR/cli-status.json" status --json
  assert_cli_contains "configHash" "CLI status output did not include configHash"

  run_cli "blacklist list" "$SMOKE_DIR/cli-blacklist.json" blacklist list --json
  assert_cli_contains "blacklist" "CLI blacklist output did not include blacklist"

  run_cli "certs" "$SMOKE_DIR/cli-certs.json" certs --json
  assert_cli_contains "certificates" "CLI certs output did not include certificates"

  run_cli "rejected-certs" "$SMOKE_DIR/cli-rejected-certs.json" rejected-certs --limit 5 --json
  assert_cli_contains "rejectedCerts" "CLI rejected-certs output did not include rejectedCerts"

  run_cli "blacklist add" "$SMOKE_DIR/cli-blacklist-add.json" blacklist add "$fingerprint" --yes --json
  assert_cli_contains "add_blacklist" "CLI blacklist add output did not include add_blacklist"

  run_cli "blacklist remove" "$SMOKE_DIR/cli-blacklist-remove.json" blacklist remove "$fingerprint" --yes --json
  assert_cli_contains "remove_blacklist" "CLI blacklist remove output did not include remove_blacklist"

  run_cli "reload certs" "$SMOKE_DIR/cli-reload-certs.json" reload certs --yes --json
  assert_cli_contains "certs" "CLI reload certs output did not include certs"

  run_cli "drain start" "$SMOKE_DIR/cli-drain-start.json" drain start --drain-timeout 1 --yes --json
  assert_cli_contains "drain_start" "CLI drain start output did not include drain_start"

  run_cli "drain cancel" "$SMOKE_DIR/cli-drain-cancel.json" drain cancel --yes --json
  assert_cli_contains "drain_cancel" "CLI drain cancel output did not include drain_cancel"

  run_cli "metrics" "$SMOKE_DIR/cli-metrics.txt" metrics
  [ -n "$cli_output" ] || fail "CLI metrics output is empty"
}

main() {
  require_cmd openssl
  require_cmd keytool
  require_cmd curl
  require_cmd java

  generate_certs
  write_config

  if [ "${1:-}" = "setup" ]; then
    log "PASS: smoke test setup completed"
    return
  fi

  build_artifacts
  start_proxy
  check_ready
  check_mtls_success
  check_mtls_reject
  check_management_auth
  check_admin_ui
  check_metrics
  check_cli

  log "PASS: smoke test completed"
}

main "$@"
