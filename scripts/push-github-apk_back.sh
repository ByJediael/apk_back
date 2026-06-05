#!/usr/bin/env bash
# Cria o repo apk_back no GitHub e envia o código (rode no seu PC, já logado no gh).
set -euo pipefail
cd "$(dirname "$0")/.."

GITHUB_USER="${GITHUB_USER:-jediael}"
REPO_NAME="${REPO_NAME:-apk_back}"
VISIBILITY="${VISIBILITY:-private}"  # ou public

if ! command -v gh >/dev/null 2>&1; then
  echo "Instale o GitHub CLI: sudo apt install gh && gh auth login"
  exit 1
fi

if git remote get-url origin 2>/dev/null; then
  echo "Remote origin já existe. Para enviar: git push -u origin master"
  exit 0
fi

gh repo create "${REPO_NAME}" \
  --"${VISIBILITY}" \
  --source=. \
  --remote=origin \
  --description="WhatsApp Backup Agent — FCM, macros, sessão, montagem fábrica" \
  --push

echo "OK: https://github.com/${GITHUB_USER}/${REPO_NAME}"
