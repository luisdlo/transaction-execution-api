#!/bin/bash
# Caso 1 — Aprobado
# ------------------------------------------------------------
# WireMock devuelve 200 APPROVED para cualquier accountId no listado
# en los stubs especiales (acc-fail, acc-error, acc-slow).
# El service persiste PENDING, llama al proveedor, y hace markExecuted.
# Espera: HTTP 201, status=EXECUTED, providerTransactionId y balanceAfter presentes.

set -u

API=http://localhost:8080/transactions
CT='Content-Type: application/json'

# Colores — se desactivan si stdout no es TTY (piped, redirigido, CI).
if [ -t 1 ]; then G='\033[0;32m'; R='\033[0;31m'; B='\033[1m'; N='\033[0m'
else G=''; R=''; B=''; N=''; fi
pass() { printf "${G}${B}✔ EXITOSA${N} — %s\n" "$1"; }
fail() { printf "${R}${B}✘ FALLIDA${N} — %s\n" "$1"; }

cat <<'BANNER'
============================================================
 Caso 1 — Aprobado
------------------------------------------------------------
 Ejecuta una transacción que el proveedor mock aprueba.
 Verifica: HTTP 201 (no 200) y status=EXECUTED en el body.
============================================================
BANNER

PAYLOAD='{"accountId":"acc-ok","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Test"}'

echo
echo "─── Petición: POST aprobado ───"
echo
echo "curl -X POST $API \\"
echo "  -H '$CT' \\"
echo "  -d '$PAYLOAD'"
echo

STATUS=$(curl -s -o /tmp/case01-body.json -w '%{http_code}' -X POST "$API" -H "$CT" -d "$PAYLOAD")
BODY_STATUS=$(jq -r .status /tmp/case01-body.json)

echo "→ HTTP $STATUS"
echo "→ body (campos clave):"
jq '{status,providerTransactionId,balanceAfter,failureCode}' /tmp/case01-body.json

echo
echo "─── Resultado ───"
if [ "$STATUS" = "201" ] && [ "$BODY_STATUS" = "EXECUTED" ]; then
  pass "HTTP 201 y status=EXECUTED. La transacción se persistió aprobada."
else
  fail "esperado HTTP 201 + status=EXECUTED, obtenido HTTP $STATUS + status=$BODY_STATUS."
fi
