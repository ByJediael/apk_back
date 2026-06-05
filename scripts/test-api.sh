#!/usr/bin/env bash
# Testa a backup-api (local ou via https://n8n.jediael.uk/webhook)
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
TOKEN="${BACKUP_API_TOKEN:-dev-token-change-me}"
DEVICE_ID="${DEVICE_ID:-mi9-se}"
AUTH="Authorization: Bearer ${TOKEN}"

echo "== Health =="
curl -sS -H "$AUTH" "${BASE_URL}/api/v1/health" | jq .

echo ""
echo "== Criar job BACKUP =="
curl -sS -X POST -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"device_id\":\"${DEVICE_ID}\",\"type\":\"BACKUP\",\"absolute_path\":\"/sdcard/Download\",\"incremental\":true}" \
  "${BASE_URL}/api/v1/admin/jobs" | jq .

echo ""
echo "== Commands (deve retornar 1 job e esvaziar fila) =="
curl -sS -H "$AUTH" "${BASE_URL}/api/v1/devices/${DEVICE_ID}/commands" | jq .

echo ""
echo "== Commands de novo (vazio) =="
curl -sS -H "$AUTH" "${BASE_URL}/api/v1/devices/${DEVICE_ID}/commands" | jq .

echo ""
echo "OK — para teste público:"
echo "  BASE_URL=https://n8n.jediael.uk/webhook BACKUP_API_TOKEN=seu-token ./scripts/test-api.sh"
