# Teste USB — SM-G570M

Atualizado: 2026-07-20 (flavors local/prod)

Ver também [`AMBIENTES.md`](AMBIENTES.md).

## Build local (USB)
```powershell
.\gradlew.bat assembleLocalDebug
adb install -r app\build\outputs\apk\local\debug\app-local-debug.apk
adb reverse tcp:8080 tcp:8080
```

No app: **Ambiente: local** · API `http://127.0.0.1:8080` · Device automático.

## Produção (VPS)
```powershell
.\gradlew.bat assembleProdRelease
adb install -r app\build\outputs\apk\prod\release\app-prod-release.apk
```

No app: **Ambiente: prod** · API `https://n8n.jediael.uk/webhook`
