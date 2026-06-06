#!/usr/bin/env bash
# Reteste pairing — menu WA Business + código Evolution + digitar.
set -euo pipefail

EVO_URL="${EVO_URL:-https://evolution.jediael.uk}"
EVO_INSTANCE="${EVO_INSTANCE:-wa-06}"
PHONE_DIGITS="${PHONE_DIGITS:-5561996435270}"

if [[ -z "${EVOLUTION_API_KEY:-}" ]]; then
  echo "export EVOLUTION_API_KEY=sua-chave"
  exit 1
fi

EVO_HEADERS=(-H "apikey: ${EVOLUTION_API_KEY}")

echo "== Pré: Acessibilidade ON | WA logado em +${PHONE_DIGITS} (root não precisa) =="
echo "   (Se mudou config de acessibilidade: desligue/ligue WhatsApp Backup)"
echo ""

echo "== 1) Instância Evolution limpa =="
curl -sS -X DELETE "${EVO_URL}/instance/delete/${EVO_INSTANCE}" "${EVO_HEADERS[@]}" >/dev/null 2>&1 || true
sleep 1
curl -sS -X POST "${EVO_URL}/instance/create" \
  "${EVO_HEADERS[@]}" -H "Content-Type: application/json" \
  -d "{\"instanceName\":\"${EVO_INSTANCE}\",\"qrcode\":false,\"integration\":\"WHATSAPP-BAILEYS\",\"number\":\"${PHONE_DIGITS}\"}" \
  | jq -c '{name: .instance.instanceName, status: .instance.status}' 2>/dev/null || true
sleep 2

echo ""
echo "== 2) Menu WA: ⋮ → Dispositivos conectados → Conectar → Com número =="
adb shell am broadcast -a com.folderbackup.agent.DEBUG_NAV_LINK
sleep 12

echo ""
echo "== 3) Código Evolution FRESCO (GET connect — expira ~60s) =="
CODE=""
for i in 1 2 3 4 5; do
  RESP=$(curl -sS "${EVO_URL}/instance/connect/${EVO_INSTANCE}?number=${PHONE_DIGITS}" \
    "${EVO_HEADERS[@]}")
  CODE=$(echo "$RESP" | jq -r '.pairingCode // empty' | tr -d '\n\r ')
  if [[ -n "$CODE" && "$CODE" != "null" ]]; then
    echo "pairingCode: $CODE"
    break
  fi
  echo "Tentativa $i: sem código"
  sleep 2
done
[[ -n "$CODE" && "$CODE" != "null" ]] || { echo "ERRO: sem pairingCode"; exit 1; }

echo ""
echo "== 4) Digitar $CODE (tela já aberta pelo menu) =="
adb shell am broadcast -a com.folderbackup.agent.DEBUG_PAIR_CODE --es code "${CODE}"

sleep 10
echo ""
echo "Logs:"
adb logcat -d -t 50 -s WaRegA11y WaLinkDeviceCoord DebugLinkDevice 2>/dev/null || true

echo ""
echo "Estado Evolution:"
curl -sS "${EVO_URL}/instance/connectionState/${EVO_INSTANCE}" "${EVO_HEADERS[@]}" | jq -c . 2>/dev/null || true

echo ""
echo "Se state=connecting e WhatsApp mostra erro:"
echo "  → pairing code da Evolution pode estar quebrado (Baileys/WhatsApp)"
echo "  → teste QR no Evolution Manager (se QR conectar, código é o problema)"
echo "  → confira CONFIG_SESSION_PHONE_VERSION vazio no EasyPanel"
