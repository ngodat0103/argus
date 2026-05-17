#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-argus}"

read -rsp "DEEPSEEK_API_KEY: " DEEPSEEK_API_KEY; echo
read -rsp "POSTGRES_PASSWORD: " POSTGRES_PASSWORD; echo
read -rsp "TELEGRAM_TOKEN (leave blank to skip): " TELEGRAM_TOKEN; echo

POSTGRES_USER="${POSTGRES_USER:-argus}"

kubectl create secret generic argus-secrets \
  --namespace "$NAMESPACE" \
  --from-literal=DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY" \
  --from-literal=POSTGRES_USER="$POSTGRES_USER" \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --from-literal=TELEGRAM_TOKEN="$TELEGRAM_TOKEN" \
  --save-config \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Secret 'argus-secrets' applied in namespace '$NAMESPACE'."
