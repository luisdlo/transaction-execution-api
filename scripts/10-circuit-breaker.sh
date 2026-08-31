#!/bin/bash
# Caso 10 — Circuit breaker
# ------------------------------------------------------------
# Después de saturar el sliding window del CB con failures 5xx, el CB
# transiciona a OPEN. La siguiente llamada NO debe tocar al proveedor:
# ProviderServiceImpl atrapa CallNotPermittedException y la traduce a
# ProviderUnavailableException con code=PROVIDER_CIRCUIT_OPEN.
#
# Config del CB (ver ProviderRestClientConfig):
#   COUNT_BASED window de 20, mínimo 10 llamadas para evaluar,
#   umbral 50% de fallas, waitDurationInOpenState=10s.
#
# ⚠ Este caso se corre al final: deja el CB abierto ~10 segundos, así que
#   cualquier llamada subsecuente contra el proveedor mientras esté OPEN
#   fallará con PROVIDER_CIRCUIT_OPEN.

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
 Caso 10 — Circuit breaker
------------------------------------------------------------
 Saturamos el sliding window con requests a acc-error (503) y
 verificamos que la siguiente llamada NO llegue al proveedor.

 ⚠ El CB queda OPEN ~10s. Corre este script AL FINAL de tu batería.
============================================================
BANNER

PAYLOAD='{"accountId":"acc-error","type":"CREDIT","amount":100,"currency":"MXN"}'

echo
echo "─── Setup: 4 requests fallidas para saturar el sliding window ───"
echo "  (cada execute() reintenta 2 veces por 503, así son ~12 hits totales)"
echo
echo "curl -X POST $API \\"
echo "  -H '$CT' \\"
echo "  -d '$PAYLOAD'"
echo "# ...repetido 4 veces"
echo

for i in 1 2 3 4; do
  curl -s -o /dev/null -X POST "$API" -H "$CT" -d "$PAYLOAD"
  echo "  request de saturación #$i enviada."
done

echo
echo "─── Setup: baseline de requests en WireMock ───"
echo
echo "curl -s $WM/requests/count | jq .count"
echo
BEFORE=$(curl -s "$WM/requests/count" | jq .count)
echo "→ baseline (después de saturar): $BEFORE requests totales."

echo
echo "─── Petición: la de prueba — DEBE cortarse por el CB, no llegar al proveedor ───"
echo
echo "curl -X POST $API \\"
echo "  -H '$CT' \\"
echo "  -d '$PAYLOAD'"
echo
FAILURE_MSG=$(curl -s -X POST "$API" -H "$CT" -d "$PAYLOAD" | jq -r .failureMessage)
AFTER=$(curl -s "$WM/requests/count" | jq .count)
DIFF=$((AFTER-BEFORE))

echo "→ failureMessage: $FAILURE_MSG"

echo
echo "─── Resultado ───"
# El CB abierto debe:
#   1) NO tocar el proveedor: DIFF == 0
#   2) Devolver ProviderUnavailable con mensaje que menciona "circuit"
CIRCUIT_MSG_OK=$(echo "$FAILURE_MSG" | grep -qi "circuit" && echo "yes" || echo "no")
if [ "$DIFF" -eq 0 ] && [ "$CIRCUIT_MSG_OK" = "yes" ]; then
  pass "0 hits al proveedor + mensaje menciona 'circuit'. El CB está OPEN y corta el flujo antes de la red."
else
  reasons=""
  [ "$DIFF" -ne 0 ] && reasons="$reasons hits=$DIFF(esperaba 0);"
  [ "$CIRCUIT_MSG_OK" != "yes" ] && reasons="$reasons mensaje no menciona 'circuit';"
  fail "el CB no cortó el flujo: $reasons"
fi
