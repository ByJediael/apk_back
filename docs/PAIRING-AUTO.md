# Pairing Evolution automático

## O que você faz (uma vez)
1. Instala o APK (`local` ou `prod`)
2. Ativa **Acessibilidade → Folder Backup Agent**
3. Deixa o app aberto ou em segundo plano (poll a cada 8s)

## O que o sistema faz sozinho
1. `POST /api/v1/admin/whatsapp/link-evolution` com `device_id` + `phone_e164`
2. Backend cria instância na Evolution, pega pairing code
3. Enfileira ação `submit_pairing_code` (e tenta FCM se existir)
4. APK busca a fila → digita o código no WhatsApp
5. Quando Evolution fica `open`, slot entra no pool

## Exemplo
```bash
curl -X POST http://127.0.0.1:8080/api/v1/admin/whatsapp/link-evolution \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"device_id\":\"dev-xxxxxxxx\",\"phone_e164\":\"+573159397209\",\"evolution_instance\":\"wa-co-3159397209\"}"
```

Device ID automático: no app (Status) ou `adb shell settings get secure android_id` → `dev-` + 8 hex.
