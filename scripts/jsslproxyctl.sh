#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_PATH="${JSSLPROXY_JAR:-$ROOT_DIR/build/libs/JSSLProxy-1.0-SNAPSHOT.jar}"

if [ ! -f "$JAR_PATH" ]; then
  if command -v gradle >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && gradle --no-daemon jar >/dev/null)
  else
    (cd "$ROOT_DIR" && ./gradlew --no-daemon jar >/dev/null)
  fi
fi

exec java -jar "$JAR_PATH" ctl "$@"
