#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_APP_SECRET:?GITHUB_APP_SECRET env var is required}"

# URL="https://unfecundated-prosily-tony.ngrok-free.dev/webhook"
URL="https://argus.datrollout.dev/webhook" # production
EVENT="${1:-push}"

if [ "$EVENT" = "push" ]; then
  PAYLOAD='{
    "ref": "refs/heads/main",
    "before": "60bc235faf901d80a7903203991987ec8297f96c",
    "after":  "85afb1535026be7dfec2c12e21b75e45619ba77f",
    "repository": { "full_name": "ngodat0103/dev-oops", "owner": { "login": "ngodat0103" } },
    "pusher": { "name": "ngodat0103" }
  }'
elif [ "$EVENT" = "pull_request" ]; then
  PAYLOAD='{
    "action": "opened",
    "number": 1,
    "pull_request": { "number": 1, "title": "Test PR" },
    "repository": { "full_name": "ngodat0103/dev-oops", "owner": { "login": "ngodat0103" } }
  }'
else
  echo "Usage: $0 [push|pull_request]"
  exit 1
fi

SIGNATURE="sha256=$(printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac "$GITHUB_APP_SECRET" | awk '{print $2}')"

echo "→ Event:     $EVENT"
echo "→ Signature: $SIGNATURE"
echo ""

curl -sv -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "X-GitHub-Event: $EVENT" \
  -H "X-GitHub-Delivery: $(uuidgen)" \
  -H "User-Agent: GitHub-Hookshot/test" \
  -H "X-Hub-Signature-256: $SIGNATURE" \
  -d "$PAYLOAD"