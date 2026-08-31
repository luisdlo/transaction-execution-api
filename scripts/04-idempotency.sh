#!/bin/bash
# Caso 4 — Idempotencia
# ------------------------------------------------------------
# Dos POST idénticos con el mismo header Idempotency-Key deben devolver
# la MISMA transacción (mismo id, mismo status, mismo balanceAfter).
#
# Cómo funciona:
#   - El repositorio tiene un índice único parcial en
#     (account_id, idempotency_key) WHERE idempotency_key IS NOT NULL.
#   - El segundo INSERT lanza DuplicateKeyException, que
#     JdbcTransactionRepository.save() atrapa y resuelve devolviendo la fila
#     existente. NO hay check-then-act.
#   - DefaultTransactionService detecta que la fila devuelta ya está en
#     un estado terminal (no PENDING) y hace short-circuit: no llama al
#     proveedor de nuevo. Sin ese guard, la segunda request cargaría dos
#     veces al cliente.

set -u

API=http://localhost:8080/transactions
CT='Content-Type: application/json'
KEY="k-$(date +%s)"

if [ -t 1 ]; then G='\033[0;32m'; R='\033[0;31m'; B='\033[1m'; N='\033[0m'
else G=''; R=''; B=''; N=''; fi
pass() { printf "${G}${B}✔ EXITOSA${N} — %s\n" "$1"; }
fail() { printf "${R}${B}✘ FALLIDA${N} — %s\n" "$1"; }

cat <<BANNER
============================================================
 Caso 4 — Idempotencia por Idempotency-Key
------------------------------------------------------------
 Dos POST idénticos con Idempotency-Key=$KEY.
 Verifica: los dos responses tienen el MISMO id.
============================================================
BANNER

PAYLOAD='{"accountId":"acc-idem","type":"CREDIT","amount":200,"currency":"MXN"}'

echo
echo "─── Petición 1: crea la transacción ───"
echo
echo "curl -X POST $API \\"
echo "  -H '$CT' \\"
echo "  -H 'Idempotency-Key: $KEY' \\"
echo "  -d '$PAYLOAD'"
echo
ID1=$(curl -s -X POST "$API" -H "$CT" -H "Idempotency-Key: $KEY" -d "$PAYLOAD" | jq -r .id)
echo "→ id: $ID1"

echo
echo "─── Petición 2: mismo body, misma key, debería devolver la misma fila ───"
echo
echo "curl -X POST $API \\"
echo "  -H '$CT' \\"
echo "  -H 'Idempotency-Key: $KEY' \\"
echo "  -d '$PAYLOAD'"
echo
ID2=$(curl -s -X POST "$API" -H "$CT" -H "Idempotency-Key: $KEY" -d "$PAYLOAD" | jq -r .id)
echo "→ id: $ID2"

echo
echo "─── Resultado ───"
if [ "$ID1" = "$ID2" ] && [ -n "$ID1" ] && [ "$ID1" != "null" ]; then
  pass "los dos POST devolvieron el mismo id ($ID1). Idempotencia funcionando."
else
  fail "id diferentes o vacíos: petición 1 = $ID1, petición 2 = $ID2."
fi
