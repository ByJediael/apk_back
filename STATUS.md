# Guia do Folder Backup Agent

Última atualização: 2026-07-20

> **Produto leads + pool + remonta:** o roadmap vivo está em  
> [`C:\Github\folder-backup-backend\ROADMAP.md`](../folder-backup-backend/ROADMAP.md)  
> Link de campanha: `{PUBLIC_BASE_URL}/r/default` (backend, não este app).

Este app faz **duas coisas diferentes**. Não misture:

| O quê | Onde fica | Para quê |
|-------|-----------|----------|
| **Backup de pastas** (fotos, Download, etc.) | Vai para o **PC/servidor** via n8n | Cópia de arquivos na nuvem/local |
| **Exportar sessão WhatsApp** | Fica no **celular** em Download | “Fotografia” da conta do WA para trocar/voltar depois |

Exportar sessão **não** envia nada para o PC sozinho. Para copiar a sessão para o PC, você precisa criar um **job de backup** da pasta `sessions/` (igual faz com Download).

---

## Parte 1 — O que é “sessão” do WhatsApp?

Quando você usa o WhatsApp Business, o Android guarda os dados da conta em pastas escondidas (precisa de **root** para copiar):

- **data/** — conversas, configurações, banco local
- **data_de/** — login, pareamento com WhatsApp Web, chaves

O app **Exportar sessão** copia essas duas pastas para:

```text
/sdcard/Download/FolderBackupAgent/sessions/session_DATA_HORA_seu-rotulo/
  ├── data/          ← cópia do user/0
  ├── data_de/      ← cópia do user_de (se existir)
  └── manifest.json ← metadados (UID, data, etc.)
```

**Restaurar** = apagar o que está no WhatsApp agora e colar de volta essa cópia (como voltar no tempo naquele aparelho).

### Antes de exportar ou restaurar

1. **Magisk** → Superusuário → permitir **Folder Backup Agent** (e usar mount master / `su -mm` se pedir).
2. No app, **Usar root** ligado.
3. **Fechar o WhatsApp Business** (não só minimizar — fechar nos apps recentes).

### Teste que você deve fazer agora (Fase 1)

Objetivo: provar que exportar e restaurar **a mesma conta** funciona (sem ficar preso no logo).

1. Instale o APK novo:
   ```bash
   cd ~/Documentos/folder-backup-agent
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Abra o WA, anote qual número/conta está ativo.
3. Feche o WA → no Folder Backup Agent → rótulo `teste-restore-v2` → **Exportar sessão**.
4. Na lista, a sessão deve mostrar algo como `500 arquivos · user_de`. Se **não** tiver `user_de`, o export falhou em parte — veja Magisk/root.
5. (Opcional) Mude algo no WA ou só lembre a conta atual.
6. Feche o WA → **Restaurar** `teste-restore-v2` → confirme no diálogo.
7. Abra o WA de novo → deve ser a **mesma conta** de antes, sem logo infinito.

**Backups antigos** (feitos antes desta versão, só com `data/` sem `data_de/`): podem travar no logo. Solução: **exportar de novo** com o APK atual e usar só essa pasta nova.

---

## Parte 2 — Dois números no mesmo celular (o que dá e o que não dá)

### O que NÃO é possível

No **mesmo ícone** do WhatsApp Business, só existe **um número por vez**. Não dá para ter número A e número B abertos ao mesmo tempo no mesmo app, só com backup.

- **Cadastrar outro número** no app = substitui a conta que está no celular.
- **WhatsApp Web** do número antigo **desconecta** quando você troca a conta no celular (ou apaga conta).
- O backup **não** “sincroniza” com o WhatsApp Web no navegador — o Web fala direto com a Meta; nosso app só guarda cópia no celular (e no PC se você mandar um job).

### O que É possível

**Opção A — App duplo (recomendado no Xiaomi)**  
Configurações → Apps → **App duplo** → ative para WhatsApp Business.

- Ícone 1 = número A (pode manter Web no A).
- Ícone 2 = número B.
- Cada um tem dados separados; exporte cada um com um rótulo (`numero-a`, `numero-b`).

**Opção B — Um ícone, alternando (export / restore)**  
Use quando só tem um WA Business instalado.

```text
PASSO 1 — Guardar número A
  • WA com número A aberto e estável
  • Fechar WA → Exportar com rótulo "numero-a"

PASSO 2 — Passar para número B
  • No WA: cadastrar número B (vai desconectar Web de A)
  • Quando B estiver ok → Fechar WA → Exportar "numero-b"

PASSO 3 — Voltar para número A
  • Fechar WA → Restaurar "numero-a" → abrir WA
  • (Web de A precisará parear de novo se tinha desconectado)

PASSO 4 — Voltar para número B
  • Fechar WA → Restaurar "numero-b"
```

**Regra de ouro:** antes de restaurar a sessão antiga, **exporte a sessão atual**. Senão você perde o estado do número que está no celular agora.

### O que evitar se quiser manter Web/QR

| Ação | Efeito |
|------|--------|
| Apagar conta no app | Mata Web na hora |
| Restaurar `numero-a` sem ter exportado `numero-b` antes | Perde o que tinha em B no celular |
| Desinstalar o app | Pode manter Web uns ~14 dias, mas perde backup local fácil |

---

## Parte 3 — Backup automático a cada ~10 dias

O app agenda sozinho (com root ligado e WA instalado):

- A cada **10 dias**, exporta uma sessão com nome `auto-2026-05-18` (data do dia).
- Depois tenta **sincronizar** — só envia ao PC se existir um **job** no backend apontando para a pasta `sessions/`.

Isso **não** atualiza o WhatsApp Web. Só garante uma cópia recente no celular (e no PC se você configurar o job no n8n).

---

## Parte 4 — Backup normal de pastas (n8n → PC)

Fluxo que já funciona para Download, etc.:

1. n8n cria job no backend.
2. Backend manda push (FCM) para o celular.
3. App sobe os arquivos para o servidor/PC.
4. n8n recebe eventos.

**Config no app:**

| Campo | Valor exemplo |
|--------|----------------|
| URL base | `http://192.168.1.9:8080` |
| Token | `12345678` |
| Device ID | `mi9-se` |
| Usar root | ligado |

**Botões úteis:** Testar API + n8n · Sincronizar agora · Salvar (registra FCM).

Firebase (uma vez): `app/google-services.json` + service account no backend — ver `folder-backup-backend/firebase/README.md`.

**Xiaomi:** desative otimização de bateria para este app, para o agendamento rodar.

---

## Resumo rápido (perguntas comuns)

**Exportei a sessão. Já está no meu PC?**  
Não, só no celular em `Download/FolderBackupAgent/sessions/`. Para o PC, crie um job de backup dessa pasta (script/backend).

**Restaurar vai fazer o Web voltar sozinho?**  
Não garantido. Restaurar coloca o celular como estava no dia do export; Web pode pedir para parear de novo.

**Posso ter dois números sem desinstalar?**  
Sim com **app duplo** (dois ícones). No mesmo ícone, só **um por vez**, alternando com export/restore.

**Por que travava no logo?**  
Backup antigo sem pasta `data_de/`. Reexporte com o APK novo.

---

## Build

```bash
cd ~/Documentos/folder-backup-agent
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
