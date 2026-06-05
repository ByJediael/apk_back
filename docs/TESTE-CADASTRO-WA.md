# Teste E2E — cadastro automático (Hero SMS + n8n)

## Pré-requisitos

1. Backend local ou VPS com FCM ativo
2. APK **0.2.0+** com Acessibilidade **WhatsApp Backup** ativada
3. App: URL + token + device ID → **Salvar**
4. Hero SMS: API key, saldo, serviço `wa` (confirmar no painel)

## Teste manual (sem n8n)

```bash
export BASE_URL=http://192.168.1.9:8080
export BACKUP_API_TOKEN=12345678

# 1. Limpar
./scripts/switch-clear.sh

# 2. Iniciar cadastro (número real da Hero)
PHONE_E164=+5511999999999 SESSION_LABEL=numero-teste-hero \
  ../folder-backup-backend/scripts/register-whatsapp.sh

# 3. Quando SMS chegar
REQUEST_ID=reg-xxxx CODE=123456 \
  ../folder-backup-backend/scripts/register-code.sh

# 4. Status
curl -sS -H "Authorization: Bearer $BACKUP_API_TOKEN" \
  "$BASE_URL/api/v1/admin/whatsapp/register-status?device_id=mi9-se" | jq .
```

## Teste com n8n

Importar [`wa-register-hero-sms.json`](../folder-backup-backend/n8n/workflows/wa-register-hero-sms.json), ajustar variáveis e executar.

Eventos no webhook n8n: `wa_register_requested`, `wa_register_code_sent`, `wa_register_status`.

## Sucesso

- `register-status` → `"status": "completed"`
- Pasta em `/sdcard/Download/FolderBackupAgent/sessions/` com rótulo escolhido
- `switch_session` com mesmo rótulo restaura a conta
