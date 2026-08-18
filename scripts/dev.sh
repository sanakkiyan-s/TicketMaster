#!/usr/bin/env bash
#
# Local development launcher.
#
# What this can actually start today:
#
#   infra/     Postgres (Citus coordinator + worker), PgBouncer, Redis,
#              Kafka + Schema Registry + Connect, Vault, MinIO.
#   frontend/  Vite dev server on :5173.
#
# What it CANNOT start: any backend service. `backend/*` contains build
# files only — zero Java/Kotlin sources exist yet (ADR-036 phase 0). So
# api-gateway is not running, and every /api call the frontend makes will
# fail at the Vite proxy until it does. That is expected, not a bug.
#
# Usage:
#   ./scripts/dev.sh            infra + frontend (default)
#   ./scripts/dev.sh infra      containers only
#   ./scripts/dev.sh frontend   Vite only (assumes infra already up)
#   ./scripts/dev.sh down       stop containers
#   ./scripts/dev.sh reset      stop containers AND delete volumes
#   ./scripts/dev.sh status     what is running
#   ./scripts/dev.sh logs [svc] tail container logs

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/infra/docker-compose.yml"
ENV_FILE="$ROOT/infra/.env"

# Services declaring a healthcheck in docker-compose.yml. Only these can
# be waited on — the others (kafka, vault, pgbouncer, schema-registry)
# report "running" the moment the process starts, which says nothing
# about readiness.
HEALTHCHECKED=(postgres-coordinator postgres-worker-1 redis minio)

WAIT_TIMEOUT_SECONDS=180

info() { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33mwarn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[0;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

compose() {
  docker compose --file "$COMPOSE_FILE" --env-file "$ENV_FILE" --project-name ticketmaster "$@"
}

require_tools() {
  command -v docker >/dev/null 2>&1 || die "docker not found on PATH"
  docker compose version >/dev/null 2>&1 || die "docker compose v2 not available (this script does not use docker-compose v1)"
  docker info >/dev/null 2>&1 || die "docker daemon not reachable — is Docker Desktop running?"
}

require_node() {
  command -v node >/dev/null 2>&1 || die "node not found on PATH"
  command -v npm >/dev/null 2>&1 || die "npm not found on PATH"
}

# Seed a .env from its committed .example, once. Never overwrites: the
# real file may hold local credentials, and infra/.env is gitignored
# precisely so it can.
seed_env() {
  local target="$1" example="$2"
  if [[ -f "$target" ]]; then
    return
  fi
  [[ -f "$example" ]] || die "missing $example — cannot seed $target"
  cp "$example" "$target"
  info "created $(basename "$target") from $(basename "$example")"
}

wait_for_health() {
  local deadline=$(( SECONDS + WAIT_TIMEOUT_SECONDS ))

  for service in "${HEALTHCHECKED[@]}"; do
    printf '    %-22s' "$service"

    while true; do
      local cid status
      cid="$(compose ps --quiet "$service" 2>/dev/null || true)"

      if [[ -n "$cid" ]]; then
        status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' "$cid" 2>/dev/null || echo starting)"

        case "$status" in
          healthy)
            printf '\033[0;32mhealthy\033[0m\n'
            break
            ;;
          nohealth)
            printf 'running (no healthcheck)\n'
            break
            ;;
          unhealthy)
            printf '\033[0;31munhealthy\033[0m\n'
            warn "check logs:  ./scripts/dev.sh logs $service"
            return 1
            ;;
        esac
      fi

      if (( SECONDS > deadline )); then
        printf '\033[0;31mtimeout\033[0m\n'
        warn "$service did not become healthy in ${WAIT_TIMEOUT_SECONDS}s"
        warn "check logs:  ./scripts/dev.sh logs $service"
        return 1
      fi

      sleep 2
    done
  done
}

print_endpoints() {
  cat <<'EOF'

    Postgres (coordinator)  localhost:5432    ticketmaster/ticketmaster
    PgBouncer               localhost:6432    transaction pooling, ADR-024
    Redis                   localhost:6380    note: 6380, not the default 6379
    Kafka                   localhost:29092
    Schema Registry         localhost:8082
    Kafka Connect           localhost:8083
    Vault                   localhost:8200    token: dev-root-token
    MinIO API / console     localhost:9000 / localhost:9001

EOF
}

start_infra() {
  require_tools
  seed_env "$ENV_FILE" "$ROOT/infra/.env.example"

  info "starting infra containers"
  compose up --detach --remove-orphans

  info "waiting for health"
  if ! wait_for_health; then
    die "infra did not come up cleanly"
  fi

  print_endpoints
}

start_frontend() {
  require_node
  seed_env "$ROOT/frontend/.env.local" "$ROOT/frontend/.env.example"

  if [[ ! -d "$ROOT/frontend/node_modules" ]]; then
    info "installing frontend dependencies"
    ( cd "$ROOT/frontend" && npm install )
  fi

  cat <<'EOF'

    Frontend        http://localhost:5173
    Backend         NOT RUNNING — backend/* has no sources yet.
                    /api/* calls will fail at the Vite proxy until
                    api-gateway exists on :8080. Expected for now.

EOF

  info "starting Vite (ctrl-c to stop; containers keep running)"
  cd "$ROOT/frontend"
  exec npm run dev
}

case "${1:-all}" in
  all)
    start_infra
    start_frontend
    ;;
  infra)
    start_infra
    info "frontend not started. run:  ./scripts/dev.sh frontend"
    ;;
  frontend)
    start_frontend
    ;;
  down)
    require_tools
    info "stopping containers (volumes preserved)"
    compose down --remove-orphans
    ;;
  reset)
    require_tools
    warn "this DELETES all local database, Kafka and MinIO data"
    read -r -p "type 'reset' to confirm: " confirm
    [[ "$confirm" == "reset" ]] || die "aborted"
    compose down --volumes --remove-orphans
    info "volumes removed"
    ;;
  status)
    require_tools
    compose ps
    ;;
  logs)
    require_tools
    shift || true
    compose logs --follow --tail 100 "$@"
    ;;
  *)
    die "unknown command '${1}'. try: all | infra | frontend | down | reset | status | logs"
    ;;
esac
