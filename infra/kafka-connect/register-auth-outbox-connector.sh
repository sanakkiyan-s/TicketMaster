#!/usr/bin/env bash
# Registers auth-outbox-connector.json against a running kafka-connect
# (infra/docker-compose.yml). Not automatic on `docker compose up` -
# Kafka Connect has no "load this connector at boot" mechanism; a
# connector is state registered via its REST API, so this is a one-time
# manual step, same as any other Kafka Connect deployment. Re-run after
# editing the config file to push the update.
set -euo pipefail

CONNECT_URL="${CONNECT_URL:-http://localhost:8083}"
CONFIG_FILE="$(dirname "$0")/auth-outbox-connector.json"
NAME="auth-service-outbox-connector"

echo "waiting for Kafka Connect at ${CONNECT_URL} ..."
until curl -sf "${CONNECT_URL}/connectors" > /dev/null; do
  sleep 2
done

if curl -sf "${CONNECT_URL}/connectors/${NAME}" > /dev/null 2>&1; then
  echo "connector ${NAME} already registered; deleting before re-registering"
  curl -sf -X DELETE "${CONNECT_URL}/connectors/${NAME}"
fi

echo "registering connector ${NAME}"
curl -sf -X POST -H "Content-Type: application/json" \
  --data "@${CONFIG_FILE}" \
  "${CONNECT_URL}/connectors"

echo
echo "status:"
curl -sf "${CONNECT_URL}/connectors/${NAME}/status"
echo
