# Ambientes: local vs produção (VPS)

O APK **não** troca servidor na tela. Você escolhe no **build**:

| Objetivo | Comando | URL vem de |
|----------|---------|------------|
| Teste USB / PC | `.\gradlew.bat assembleLocalDebug` | `api.local.url` |
| Produção VPS | `.\gradlew.bat assembleProdRelease` | `api.prod.url` |

APKs gerados:
- `app/build/outputs/apk/local/debug/app-local-debug.apk`
- `app/build/outputs/apk/prod/release/app-prod-release.apk`

## local.properties (não vai pro git)

```properties
api.local.url=http://127.0.0.1:8080
api.local.token=12345678

api.prod.url=https://n8n.jediael.uk/webhook
api.prod.token=SEU_TOKEN_FORTE_DA_VPS
```

Na VPS, o token do APK deve ser o mesmo `BACKUP_API_TOKEN` do `.env` do backend.

## Na tela do app
Aparece `Ambiente: local` ou `Ambiente: prod` + a URL — confira antes de montar WhatsApp.

## Android Studio
Build Variants → escolha `localDebug` ou `prodRelease` → Run.
