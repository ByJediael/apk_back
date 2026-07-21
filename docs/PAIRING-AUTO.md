# Pairing Evolution automático

## Fluxo (ordem fixa)

1. **Fechar WhatsApp na lista de apps recentes** — botão quadrado → X ou arrastar card → depois force-stop
2. **Apagar instância Evolution** (automático se código falhar; ou `force_new: true` no próximo link)
3. **Criar instância Evolution** (backend, WA fechado)
4. **Abrir na tela inicial** — `macro_open_whatsapp`
5. **Pedir código** (celular na inicial) → **depois** navegar dispositivos conectados
6. Digitar código → anti-golpe (OK + Conectar) → nome → `completed`

> Se o código falhar, o WA não aceita outro até sair dos **apps recentes**. O próximo `force_new` faz: recentes + force-stop + apaga instância + código novo.

## O que você faz (uma vez)

1. Instala o APK (`local` ou `prod`)
2. Ativa **Acessibilidade → Folder Backup Agent**
3. Deixa o app aberto ou em segundo plano (poll a cada 8s)

## Teste rápido

```powershell
POST http://127.0.0.1:8080/api/v1/admin/whatsapp/link-evolution
{
  "device_id": "dev-42a0e8c7",
  "phone_e164": "+573159397209",
  "evolution_instance": "wa-co-3159397209",
  "navigate_first": true,
  "force_new": true
}
```

Fases esperadas: `awaiting_force_stop` → `awaiting_home` → `awaiting_navigation` (já com `pairing_code`) → `pairing_queued` → `completed`

## Produção 4G

- Sem USB/ADB: APK + poll/FCM; Evolution na VPS
- `force_new: true` só na remontagem; operação normal reutiliza sessão
