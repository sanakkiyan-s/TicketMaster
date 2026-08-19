#!/usr/bin/env bash
#
# Local development launcher.
#
# What this can actually start today:
#
#   infra/     Postgres (Citus coordinator + worker), PgBouncer, Redis,
#              Kafka + Schema Registry + Connect, Vault, MinIO.
#   backend/   auth-service (:8081), api-gateway (:8080), user-service
#              (:8082) - the only three modules with real sources so far
#              (ADR-036 Phase 1). Built and run as containers via the
#              same docker-compose.yml, gated behind the "backend"
#              profile so plain infra startup never pays their build
#              time. The other 12 backend/* directories hold build
#              files only - nothing to run.
#   frontend/  Vite dev server on :5173.
#
# Usage:
#   ./scripts/dev.sh            infra + backend + frontend (default)
#   ./scripts/dev.sh infra      containers only
#   ./scripts/dev.sh backend    auth-service/api-gateway/user-service (assumes infra already up)
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

# Backend containers declare their own healthcheck (Dockerfile installs
# curl for exactly this) - same wait_for_health mechanism as infra,
# different list, since these only run under the "backend" profile.
BACKEND_HEALTHCHECKED=(auth-service api-gateway user-service)

WAIT_TIMEOUT_SECONDS=180

# Host port the coordinator is published on. NOT 5432: a native Postgres
# install owns that on this machine, and because Postgres answers an
# unknown role with the same "password authentication failed" it uses for a
# wrong password, connecting to the squatter looked exactly like a bad
# credential. Keep in sync with the "5433:5432" mapping in
# docker-compose.yml and with DB_PORT's default in each service's
# application.yml.
HOST_PG_PORT=5433

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

  for service in "$@"; do
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

# The container healthcheck proves the coordinator authenticates. It does
# NOT prove that the published host port reaches THAT coordinator — a
# native Postgres install or a container from another project can own the
# port, and a service would then talk to the wrong database.
#
# Postgres reports "password authentication failed" for an unknown role as
# well as for a wrong password (deliberately, to block user enumeration),
# so the error alone cannot tell those apart. system_identifier can: it is
# generated once by initdb and is unique per cluster. Same value on both
# paths means the same server.
verify_host_port_reaches_container() {
  local in_container from_host

  # PGPASSWORD is required even here: -h 127.0.0.1 is a TCP connection, so
  # it authenticates for real rather than matching the image's
  # `local all all trust` socket rule.
  in_container="$(compose exec -T \
    --env "PGPASSWORD=${POSTGRES_PASSWORD:-ticketmaster}" postgres-coordinator \
    psql -h 127.0.0.1 -U "${POSTGRES_USER:-ticketmaster}" -d "${POSTGRES_DB:-ticketmaster}" \
    -tAc 'SELECT system_identifier FROM pg_control_system()' 2>/dev/null | tr -d '[:space:]')"

  if [[ -z "$in_container" ]]; then
    # Reachable in practice only if the healthcheck passed but this query
    # did not — e.g. the role exists with a different password than
    # infra/.env claims, which happens when the volume was initialised by
    # an earlier run (POSTGRES_PASSWORD is read ONLY by initdb, on an empty
    # data directory, and is inert forever after).
    warn "coordinator did not accept ${POSTGRES_USER:-ticketmaster}/<password from infra/.env>"
    warn "the stored password likely predates infra/.env. wipe the volume:  ./scripts/dev.sh reset"
    return 1
  fi

  # Run from a throwaway container rather than the host, so this needs no
  # psql client installed on Windows. host.docker.internal is Docker
  # Desktop's route back to the host's published ports.
  from_host="$(docker run --rm \
    --env "PGPASSWORD=${POSTGRES_PASSWORD:-ticketmaster}" \
    citusdata/citus:12.1 \
    psql -h host.docker.internal -p "$HOST_PG_PORT" -U "${POSTGRES_USER:-ticketmaster}" \
      -d "${POSTGRES_DB:-ticketmaster}" \
      -tAc 'SELECT system_identifier FROM pg_control_system()' 2>/dev/null | tr -d '[:space:]')"

  if [[ -z "$from_host" ]]; then
    warn "localhost:$HOST_PG_PORT did not accept ${POSTGRES_USER:-ticketmaster}, even though the container did."
    warn "something else is listening on $HOST_PG_PORT, or its credentials differ."
    warn "find it:  netstat -ano | grep :$HOST_PG_PORT"
    return 1
  fi

  if [[ "$from_host" != "$in_container" ]]; then
    warn "localhost:$HOST_PG_PORT reaches a DIFFERENT Postgres than the compose coordinator"
    warn "  coordinator system_identifier : $in_container"
    warn "  localhost:$HOST_PG_PORT       : $from_host"
    warn "stop the other server (a native install, or another project's container) and retry"
    return 1
  fi

  printf '    %-22s\033[0;32mreaches the coordinator\033[0m\n' "localhost:$HOST_PG_PORT"
}

print_endpoints() {
  cat <<'EOF'

    Postgres (coordinator)  localhost:5433    ticketmaster/ticketmaster
    PgBouncer               localhost:6432    transaction pooling, ADR-024
    Redis                   localhost:6380    note: 6380, not the default 6379
    Kafka                   localhost:29092
    Schema Registry         localhost:8082
    Kafka Connect           localhost:8083
    Vault                   localhost:8200    token: dev-root-token
    MinIO API / console     localhost:9000 / localhost:9001

EOF
}

print_backend_endpoints() {
  cat <<'EOF'

    api-gateway     http://localhost:8080
    auth-service    http://localhost:8081
    user-service    http://localhost:8090   (container's own port is 8082 - 8082 is schema-registry's host mapping)

EOF
}

# compose gets the env file via --env-file, but this script's own checks
# need the same values. Sourced with `set -a` so every assignment is
# exported to the docker invocations below.
load_env() {
  [[ -f "$ENV_FILE" ]] || return 0
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
}

start_infra() {
  require_tools
  seed_env "$ENV_FILE" "$ROOT/infra/.env.example"
  load_env

  info "starting infra containers"
  compose up --detach --remove-orphans

  info "waiting for health"
  if ! wait_for_health "${HEALTHCHECKED[@]}"; then
    die "infra did not come up cleanly"
  fi

  if ! verify_host_port_reaches_container; then
    die "the coordinator is healthy but localhost:$HOST_PG_PORT does not reach it"
  fi

  print_endpoints
}

start_backend() {
  require_tools

  info "building and starting backend containers (auth-service, api-gateway, user-service)"
  info "first build compiles the whole Gradle multi-module tree - can take a few minutes"
  compose --profile backend up --detach --build

  info "waiting for health"
  if ! wait_for_health "${BACKEND_HEALTHCHECKED[@]}"; then
    die "backend did not come up cleanly"
  fi

  print_backend_endpoints
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

EOF

  info "starting Vite (ctrl-c to stop; containers keep running)"
  cd "$ROOT/frontend"
  exec npm run dev
}

case "${1:-all}" in
  all)
    start_infra
    start_backend
    start_frontend
    ;;
  infra)
    start_infra
    info "backend not started. run:  ./scripts/dev.sh backend"
    ;;
  backend)
    start_backend
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
    die "unknown command '${1}'. try: all | infra | backend | frontend | down | reset | status | logs"
    ;;
esac
