#!/bin/bash
# Caso 3 — Read timeout (unknown state)
# ------------------------------------------------------------
# acc-slow hace que WireMock tarde más que el read-timeout del cliente.
# ProviderServiceImpl.translateIoException() detecta HttpTimeoutException
# y lo mapea a ProviderUnknownStateException (NO ProviderUnavailable).
#
# Regla crítica: read timeout NO SE REINTENTA. La petición ya salió del
# cliente; el cargo pudo haberse ejecutado del lado del proveedor.
# Reintentar duplicaría dinero real. Se marca FAILED para reconciliación
# manual.
#
# Verificamos dos cosas:
#   1) Sólo 1 request llega a WireMock (no 3), lo que prueba el no-retry.
#   2) El tiempo total es cercano al read-timeout, no 3x más.

set -u

API=http://localhost:8080/transactions
WM=http://localhost:8081/__admin
CT='Content-Type: application/json'

if [ -t 1 ]; then G='\033[0;32m'; R='\033[0;31m'; B='\033[1m'; N='\033[0m'
else G=''; R=''; B=''; N=''; fi
pass() { printf "${G}${B}✔ EXITOSA${N} — %s\n" "$1"; }
fail() { printf "${R}${B}✘ FALLIDA${N} — %s\n" "$1"; }

cat <<'BANNER'
============================================================
 Caso 3 — Read timeout (unknown state, no retry)
------------------------------------------------------------
 acc-slow tarda más que el read-timeout configurado.
 Verifica: status=FAILED, y EXACTAMENTE 1 hit al proveedor.
============================================================
BANNER

echo
echo "─── Setup: baseline de requests en WireMock ───"
echo
echo "curl -s $WM/requests/count | jq .count"
echo
BEFORE=$(curl -s "$WM/requests/count" | jq .count)
echo "→ baseline: $BEFORE requests"

PAYLOAD='{"accountId":"acc-slow","type":"CREDIT","amount":100,"currency":"MXN"}'

echo
echo "─── Petición: POST que dispara read timeout (cronometrado) ───"
echo
echo "curl -X POST $API \\"
echo "  -H '$CT' \\"
echo "  -d '$PAYLOAD'"
echo

START=$(date +%s)
STATUS=$(curl -s -o /tmp/case03-body.json -w '%{http_code}' -X POST "$API" -H "$CT" -d "$PAYLOAD")
END=$(date +%s)
ELAPSED=$((END-START))

AFTER=$(curl -s "$WM/requests/count" | jq .count)
DIFF=$((AFTER-BEFORE))
BODY_STATUS=$(jq -r .status /tmp/case03-body.json)

echo "→ HTTP $STATUS   (tomó ~${ELAPSED}s)"
echo "→ body (campos clave):"
jq '{status,failureMessage}' /tmp/case03-body.json

echo
echo "─── Resultado ───"
if [ "$STATUS" = "201" ] && [ "$BODY_STATUS" = "FAILED" ] && [ "$DIFF" -eq 1 ]; then
  pass "1 sólo hit al proveedor + status=FAILED. Read timeout NO reintentado."
else
  reasons=""
  [ "$STATUS" != "201" ] && reasons="$reasons HTTP=$STATUS(esperaba 201);"
  [ "$BODY_STATUS" != "FAILED" ] && reasons="$reasons status=$BODY_STATUS(esperaba FAILED);"
  [ "$DIFF" -ne 1 ] && reasons="$reasons hits=$DIFF(esperaba 1);"
  fail "algo no cuadra: $reasons"
fi
