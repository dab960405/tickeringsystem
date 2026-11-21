#!/usr/bin/env bash
# Run from a checkout: bash scripts/local.sh <command>
set -euo pipefail
cd "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

require_command() {
  command -v "$1" >/dev/null || { printf 'Falta la herramienta: %s\n' "$1" >&2; exit 1; }
}

load_environment() {
  [[ -f .env ]] || { printf 'Primero ejecuta: bash scripts/local.sh init\n' >&2; exit 1; }
  set -a
  source ./.env
  set +a
}

check_java() {
  require_command java
  java -version 2>&1 | grep -Eq 'version "21\.' || {
    printf 'El proyecto requiere Java 21. Revisa JAVA_HOME y PATH.\n' >&2
    exit 1
  }
}

check_docker() {
  require_command docker
  docker compose version
  docker info --format '{{.ServerVersion}}' || {
    printf 'Docker debe estar iniciado y accesible para este usuario.\n' >&2
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
    print('.env ya existe; se conserva sin cambios.')
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
    print('.env creado con secretos locales aleatorios y permisos 600.')
PY
    ;;
  doctor)
    check_java
    require_command python3
    check_docker
    printf 'Herramientas disponibles. El arranque y las pruebas aún deben ejecutarse.\n'
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
    [[ -d frontend/node_modules ]] || { printf 'Primero ejecuta: cd frontend && npm ci\n' >&2; exit 1; }
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
      'Uso: bash scripts/local.sh init|doctor|infra|run|ui|test|smoke|adversarial|stop' \
      'init: crea .env si no existe; doctor: verifica herramientas.' \
      'infra: inicia PostgreSQL y Redis; run: compila sin pruebas e inicia la API.' \
      'ui: inicia la interfaz y el simulador local en http://localhost:5173.' \
      'test: ejecuta la suite completa; smoke: verifica la API ya iniciada.' \
      'adversarial [--with-integration]: pruebas de fallos locales con evidencia JSON/Markdown.' \
      'stop: detiene PostgreSQL y Redis conservando sus datos.'
    ;;
  *) printf 'Comando desconocido: %s\n' "$1" >&2; exit 2 ;;
esac
