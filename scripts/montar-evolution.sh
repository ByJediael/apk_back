#!/usr/bin/env bash
# Monta Evolution no celular conectado (J5 etc.): menu WA + pairing code.
#
# Uso:
#   export EVOLUTION_API_KEY=sua-chave   # uma vez por sessão
#   ./scripts/montar-evolution.sh 5561996435270
#
# Ou sem argumento — o script pergunta o número.
#
# Antes no celular:
#   - WhatsApp Business LOGADO nesse número
#   - Acessibilidade → WhatsApp Backup ON
#   - APK debug instalado (./gradlew assembleDebug && adb install -r app-debug.apk)
#   - Root NÃO precisa

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "$ROOT_DIR/.env" ]]; then
  # shellcheck disable=SC1091
  set -a && source "$ROOT_DIR/.env" && set +a
fi

EVO_URL="${EVO_URL:-https://evolution.jediael.uk}"
EVO_INSTANCE="${EVO_INSTANCE:-wa-j5}"
ADB_DEVICE="${ADB_DEVICE:-}"

PHONE_DIGITS="${1:-}"
if [[ -z "$PHONE_DIGITS" ]]; then
  read -r -p "Número do WhatsApp (só dígitos, ex: 5561996435270): " PHONE_DIGITS
else
  PHONE_DIGITS="$(echo "$PHONE_DIGITS" | tr -cd '0-9')"
fi

if [[ -z "${PHONE_DIGITS:-}" ]]; then
  echo "ERRO: informe o número."
  exit 1
fi

if [[ -z "${EVOLUTION_API_KEY:-}" ]]; then
  read -r -s -p "EVOLUTION_API_KEY (EasyPanel): " EVOLUTION_API_KEY
  echo ""
fi
[[ -n "${EVOLUTION_API_KEY:-}" ]] || { echo "ERRO: defina EVOLUTION_API_KEY"; exit 1; }

ADB=(adb)
if [[ -n "$ADB_DEVICE" ]]; then
  ADB+=( -s "$ADB_DEVICE" )
fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "ERRO: celular não conectado (adb devices)."
  exit 1
fi

EVO_HEADERS=(-H "apikey: ${EVOLUTION_API_KEY}")

echo ""
echo "=== Montar Evolution ==="
echo "  Número: +${PHONE_DIGITS}"
echo "  Instância: ${EVO_INSTANCE}"
echo "  Celular: $("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
echo ""
echo "  Confirme no celular: WA logado + Acessibilidade ON"
if [[ -z "${MONTAR_SKIP_CONFIRM:-}" ]]; then
  read -r -p "  Enter para continuar…"
fi

echo ""
echo "== 1/4 Evolution: instância limpa =="
curl -sS -X DELETE "${EVO_URL}/instance/delete/${EVO_INSTANCE}" "${EVO_HEADERS[@]}" >/dev/null 2>&1 || true
sleep 1
curl -sS -X POST "${EVO_URL}/instance/create" \
  "${EVO_HEADERS[@]}" -H "Content-Type: application/json" \
  -d "{\"instanceName\":\"${EVO_INSTANCE}\",\"qrcode\":false,\"integration\":\"WHATSAPP-BAILEYS\",\"number\":\"${PHONE_DIGITS}\"}" \
  | jq -c '{name: .instance.instanceName, status: .instance.status}' 2>/dev/null || true
sleep 2

echo ""
echo "== 2/4 WhatsApp: menu → Conectar com número =="
"${ADB[@]}" shell am broadcast -a com.folderbackup.agent.DEBUG_NAV_LINK
sleep 14

echo ""
echo "== 3/4 Evolution: código de pareamento =="
CODE=""
for _ in 1 2 3 4 5; do
  RESP=$(curl -sS "${EVO_URL}/instance/connect/${EVO_INSTANCE}?number=${PHONE_DIGITS}" \
    "${EVO_HEADERS[@]}")
  CODE=$(echo "$RESP" | jq -r '.pairingCode // empty' | tr -d '\n\r ')
  if [[ -n "$CODE" && "$CODE" != "null" ]]; then
    echo "  pairingCode: $CODE"
    break
  fi
  sleep 2
done
[[ -n "$CODE" && "$CODE" != "null" ]] || {
  echo "ERRO: Evolution não devolveu pairingCode."
  echo "  Confira número, apikey e instância ${EVO_INSTANCE} no painel."
  exit 1
}

echo ""
echo "== 4/4 Digitar código no celular =="
"${ADB[@]}" shell am broadcast -a com.folderbackup.agent.DEBUG_PAIR_CODE --es code "${CODE}"
sleep 8

echo ""
echo "== Estado Evolution =="
curl -sS "${EVO_URL}/instance/connectionState/${EVO_INSTANCE}" "${EVO_HEADERS[@]}" | jq . 2>/dev/null || true

echo ""
echo "Pronto. Se não conectou, logs:"
echo "  adb logcat -s WaRegA11y WaLinkDeviceCoord DebugLinkDevice"
