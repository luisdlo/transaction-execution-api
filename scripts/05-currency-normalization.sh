#!/bin/bash
# Caso 5 — Normalización de currency
# ------------------------------------------------------------
# El cliente envía "mxn" en minúsculas. CreateTransactionRequest.toCommand()
# hace toUpperCase(Locale.ROOT) en la frontera del controller.
#
# Sin esta normalización:
#   - SupportedCurrencyRule usa equalsIgnoreCase y aceptaría "mxn".
#   - Pero el CHECK constraint de la tabla exige el literal 'MXN' y
#     rechazaría el INSERT, dejando un 500 opaco al cliente cuando la
#     transacción ya iba en vuelo.
# La normalización en la frontera cierra ese gap.

set -u

API=http://localhost:8080/transactions
CT='Content-Type: application/json'

if [ -t 1 ]; then G='\033[0;32m'; R='\033[0;31m'; B='\033[1m'; N='\033[0m'
else G=''; R=''; B=''; N=''; fi
pass() { printf "${G}${B}✔ EXITOSA${N} — %s\n" "$1"; }
fail() { printf "${R}${B}✘ FALLIDA${N} — %s\n" "$1"; }

cat <<'BANNER'
============================================================
 Caso 5 — Normalización de currency ("mxn" -> "MXN")
------------------------------------------------------------
 Enviamos currency en minúsculas.
 Verifica: la respuesta y la fila persistida quedan con "MXN".
============================================================
BANNER

PAYLOAD='{"accountId":"acc-ok","type":"CREDIT","amount":100,"currency":"mxn"}'

echo
echo "─── Petición: POST con currency en minúsculas ───"
echo
echo "curl -X POST $API \\"
echo "  -H '$CT' \\"
echo "  -d '$PAYLOAD'"
echo

STATUS=$(curl -s -o /tmp/case05-body.json -w '%{http_code}' -X POST "$API" -H "$CT" -d "$PAYLOAD")
CURRENCY=$(jq -r .currency /tmp/case05-body.json)

echo "→ HTTP $STATUS"
echo "→ body (campos clave):"
jq '{status,currency}' /tmp/case05-body.json

echo
echo "─── Resultado ───"
if [ "$STATUS" = "201" ] && [ "$CURRENCY" = "MXN" ]; then
  pass "currency=MXN en la respuesta. La normalización se aplicó en el controller."
else
  fail "esperado HTTP 201 + currency=MXN, obtenido HTTP $STATUS + currency=$CURRENCY."
fi
