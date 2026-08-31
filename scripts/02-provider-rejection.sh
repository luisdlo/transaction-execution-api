#!/bin/bash
# Caso 2 — Rechazo del proveedor
# ------------------------------------------------------------
# WireMock responde 400 INSUFFICIENT_FUNDS para accountId=acc-fail.
# ProviderServiceImpl.dispatch() traduce el 4xx a ProviderRejectedException.
# DefaultTransactionService la atrapa y llama markRejected: la fila queda
# en REJECTED con failureCode y failureMessage.
#
# Punto clave: la API devuelve 201, NO 4xx. Un rechazo del proveedor es
# una transición legítima de la máquina de estados, no un error HTTP.

set -u

API=http://localhost:8080/transactions
CT='Content-Type: application/json'

if [ -t 1 ]; then G='\033[0;32m'; R='\033[0;31m'; B='\033[1m'; N='\033[0m'
else G=''; R=''; B=''; N=''; fi
pass() { printf "${G}${B}✔ EXITOSA${N} — %s\n" "$1"; }
fail() { printf "${R}${B}✘ FALLIDA${N} — %s\n" "$1"; }

cat <<'BANNER'
============================================================
 Caso 2 — Rechazo del proveedor
------------------------------------------------------------
 acc-fail dispara 400 INSUFFICIENT_FUNDS en WireMock.
 Verifica: HTTP 201 (NO 4xx) y status=REJECTED con code.
============================================================
BANNER

PAYLOAD='{"accountId":"acc-fail","type":"DEBIT","amount":100,"currency":"MXN"}'

echo
echo "─── Petición: POST que el proveedor rechaza ───"
echo
echo "curl -X POST $API \\"
echo "  -H '$CT' \\"
echo "  -d '$PAYLOAD'"
echo

STATUS=$(curl -s -o /tmp/case02-body.json -w '%{http_code}' -X POST "$API" -H "$CT" -d "$PAYLOAD")
BODY_STATUS=$(jq -r .status /tmp/case02-body.json)
FAILURE_CODE=$(jq -r .failureCode /tmp/case02-body.json)

echo "→ HTTP $STATUS"
echo "→ body (campos clave):"
jq '{status,failureCode,failureMessage}' /tmp/case02-body.json

echo
echo "─── Resultado ───"
if [ "$STATUS" = "201" ] && [ "$BODY_STATUS" = "REJECTED" ] && [ "$FAILURE_CODE" = "INSUFFICIENT_FUNDS" ]; then
  pass "HTTP 201 con status=REJECTED y failureCode=INSUFFICIENT_FUNDS. Un rechazo del proveedor NO es error HTTP."
else
  fail "esperado HTTP 201 + REJECTED + INSUFFICIENT_FUNDS; obtenido HTTP $STATUS + status=$BODY_STATUS + code=$FAILURE_CODE."
fi
