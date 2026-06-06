#!/usr/bin/env bash
# Teste pairing via USB (APK debug) + Evolution API direta.
set -euo pipefail

DEVICE="${ADB_DEVICE:-}"
ADB=(adb)
if [[ -n "$DEVICE" ]]; then ADB+=( -s "$DEVICE" ); fi

EVO_URL="${EVO_URL:-https://evolution.jediael.uk}"
EVO_INSTANCE="${EVO_INSTANCE:-wa-06}"
PHONE_DIGITS="${PHONE_DIGITS:-5561996435270}"

fetch_pairing_code() {
  local attempt code resp
  for attempt in 1 2 3 4; do
    resp=$(curl -sS "${EVO_URL}/instance/connect/${EVO_INSTANCE}?number=${PHONE_DIGITS}" \
      -H "apikey: ${EVOLUTION_API_KEY}")
    code=$(echo "$resp" | jq -r '.pairingCode // empty')
    if [[ -n "$code" && "$code" != "null" ]]; then
      echo "$code"
      return 0
    fi
    echo "Tentativa $attempt: sem pairingCode — $(echo "$resp" | jq -c '{status, error, message: .response.message}')" >&2
    sleep 2
  done
  return 1
}

ensure_evolution_instance() {
  echo "== Evolution: garantir instância ${EVO_INSTANCE} =="
  local create_resp
  create_resp=$(curl -sS -X POST "${EVO_URL}/instance/create" \
    -H "apikey: ${EVOLUTION_API_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"instanceName\":\"${EVO_INSTANCE}\",\"qrcode\":false,\"integration\":\"WHATSAPP-BAILEYS\",\"number\":\"${PHONE_DIGITS}\"}")
  echo "$create_resp" | jq -c . 2>/dev/null || echo "$create_resp"
  # 403/409 = já existe — ok
  sleep 1
  # Se ficou presa em QR/connecting, logout ajuda
  curl -sS -X DELETE "${EVO_URL}/instance/logout/${EVO_INSTANCE}" \
    -H "apikey: ${EVOLUTION_API_KEY}" >/dev/null 2>&1 || true
  sleep 1
}

echo "== Dispositivo USB =="
"${ADB[@]}" devices -l | grep -v '^List' | grep device || { echo "Nenhum celular USB"; exit 1; }

if [[ -z "${EVOLUTION_API_KEY:-}" ]]; then
  echo "ERRO: export EVOLUTION_API_KEY=sua-chave"
  exit 1
fi

echo ""
ensure_evolution_instance

echo ""
echo "== 1) Evolution: pairing code =="
CODE=""
if CODE=$(fetch_pairing_code); then
  echo "pairingCode: $CODE"
else
  echo "ERRO: Evolution não devolveu pairingCode."
  echo "  - Confira se ${EVO_INSTANCE} existe no Evolution Manager"
  echo "  - DISCONNECT na instância (não clique Get QR Code antes)"
  echo "  - Rode de novo este script"
  exit 1
fi

echo ""
echo "== 2) APK: tela 'Insira o código' =="
echo "   Celular: desbloqueado + Acessibilidade ON + Root ON"
read -r -p "   Enter para abrir tela no celular…"
"${ADB[@]}" shell am broadcast -a com.folderbackup.agent.DEBUG_NAV_LINK
sleep 3

echo ""
echo "== 3) APK: digitar código $CODE =="
read -r -p "   Enter para digitar (código expira rápido!)…"
"${ADB[@]}" shell am broadcast -a com.folderbackup.agent.DEBUG_PAIR_CODE --es code "$CODE"

echo ""
echo "Logs: adb logcat -s WhatsappOpenHelper WaLinkDeviceCoord WaRegA11y DebugLinkDevice"
