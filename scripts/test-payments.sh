#!/usr/bin/env bash
# Manual smoke test for POST /payments (idempotency behaviour).
# Usage: ./scripts/test-payments.sh [base_url]   (default: http://localhost:8080)
set -u

BASE_URL="${1:-http://localhost:8080}"
URL="$BASE_URL/payments"
BODY='{"accountId":"ACC-001","amount":99.95,"currency":"EUR"}'

new_key() { uuidgen | tr '[:upper:]' '[:lower:]'; }
section() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

post() { # $1 = idempotency key ("" to omit header), $2 = body
  if [ -n "$1" ]; then
    curl -s -i -X POST "$URL" -H "Content-Type: application/json" -H "Idempotency-Key: $1" -d "$2"
  else
    curl -s -i -X POST "$URL" -H "Content-Type: application/json" -d "$2"
  fi
  echo
}

section "1. New payment (expect 201)"
post "$(new_key)" "$BODY"

section "2. Retry with the same key (expect same transactionId + Idempotency-Replayed: true)"
KEY=$(new_key)
echo "Key: $KEY"
echo "-- first call"
post "$KEY" "$BODY"
echo "-- second call"
post "$KEY" "$BODY"

section "3. 5 concurrent requests with the same key (expect one 201, rest 409 or replayed 201)"
KEY=$(new_key)
for _ in 1 2 3 4 5; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST "$URL" \
    -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" -d "$BODY" &
done
wait

section "4. Missing Idempotency-Key header (expect 400)"
post "" "$BODY"

section "5. Invalid body: negative amount, empty currency (expect 400)"
post "$(new_key)" '{"accountId":"ACC-001","amount":-5,"currency":""}'
