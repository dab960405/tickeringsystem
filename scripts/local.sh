#!/usr/bin/env bash
# Run from a checkout: bash scripts/local.sh <command>
set -euo pipefail
cd "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

require_command() {
  command -v "$1" >/dev/null || { printf 'Missing required command: %s\n' "$1" >&2; exit 1; }
}

load_environment() {
  [[ -f .env ]] || { printf 'Run this first: bash scripts/local.sh init\n' >&2; exit 1; }
  set -a
  source ./.env
  set +a
}

check_java() {
  require_command java
  java -version 2>&1 | grep -Eq 'version "21\.' || {
    printf 'Java 21 is required. Check JAVA_HOME and PATH.\n' >&2
    exit 1
  }
}

check_docker() {
  require_command docker
  docker compose version
  docker info --format '{{.ServerVersion}}' || {
    printf 'Docker must be running and accessible to the current user.\n' >&2
    exit 1
  }
}

case "${1:-help}" in
  init)
    require_command python3
    python3 - <<'PY'
import base64
import os
import secrets
from pathlib import Path

path = Path('.env')
if path.exists():
    print('.env already exists; it will not be modified.')
else:
    replacements = {
        'POSTGRES_PASSWORD': secrets.token_hex(24),
        'REDIS_PASSWORD': secrets.token_hex(24),
        'MONGO_ROOT_PASSWORD': secrets.token_hex(24),
        'JWT_SECRET': base64.b64encode(secrets.token_bytes(32)).decode(),
        'PAYMENT_WEBHOOK_SECRET': secrets.token_hex(32),
        'NOTIFICATION_API_KEY': secrets.token_hex(32),
        'GRAFANA_ADMIN_PASSWORD': secrets.token_hex(24),
    }
    lines = []
    for line in Path('.env.example').read_text().splitlines():
        key = line.partition('=')[0]
        if key in replacements:
            line = key + '=' + replacements[key]
        elif key == 'MONGODB_URI':
            line = ('MONGODB_URI=mongodb://notification:' + replacements['MONGO_ROOT_PASSWORD']
                    + '@localhost:27017/notifications?authSource=admin')
        lines.append(line)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as target:
        target.write('\n'.join(lines) + '\n')
    print('.env created with randomized local secrets and mode 600.')
PY
    ;;
  doctor)
    check_java
    require_command python3
    check_docker
    printf 'Required tools are available. Startup and tests have not yet been run.\n'
    ;;
  infra)
    load_environment
    check_docker
    docker compose --profile infra up -d --wait --wait-timeout 120 postgres redis
    ;;
  run)
    load_environment
    check_java
    ./mvnw -B -pl ticketing-api -am package -DskipTests
    exec java -jar ticketing-api/target/ticketing-api-0.1.0-SNAPSHOT.jar
    ;;
  test)
    check_java
    check_docker
    exec ./mvnw -B clean verify
    ;;
  smoke)
    load_environment
    require_command python3
    export TICKETING_BASE_URL="${TICKETING_BASE_URL:-http://localhost:${TICKETING_API_PORT:-8080}}"
    exec python3 scripts/local-smoke.py
    ;;
  adversarial)
    load_environment
    require_command python3
    shift
    exec python3 scripts/adversarial.py "$@"
    ;;
  ui)
    load_environment
    require_command python3
    if [[ -n "${NODE_HOME:-}" ]]; then export PATH="$NODE_HOME/bin:$PATH"; fi
    require_command npm
    [[ -d frontend/node_modules ]] || { printf 'Run this first: cd frontend && npm ci\n' >&2; exit 1; }
    npm --prefix frontend run build
    exec python3 frontend/dev_server.py
    ;;
  stop)
    load_environment
    check_docker
    docker compose --profile infra stop postgres redis
    ;;
  help|-h|--help)
    printf '%s\n' \
      'Usage: bash scripts/local.sh init|doctor|infra|run|ui|test|smoke|adversarial|stop' \
      'init: create .env if missing; doctor: check required tools.' \
      'infra: start PostgreSQL and Redis; run: package without tests and start the API.' \
      'ui: start the local UI and simulator at http://localhost:5173.' \
      'test: run the full suite; smoke: verify the running API.' \
      'adversarial [--with-integration]: local failure scenarios with JSON/Markdown evidence.' \
      'stop: stop PostgreSQL and Redis without removing stored data.'
    ;;
  *) printf 'Unknown command: %s\n' "$1" >&2; exit 2 ;;
esac
