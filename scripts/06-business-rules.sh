#!/bin/bash
# Caso 6 — Reglas de negocio (422 Unprocessable Entity)
# ------------------------------------------------------------
# Las reglas se aplican en DefaultTransactionService.execute() ANTES de
# persistir cualquier fila. Si alguna truena con BusinessRuleViolationException,
# el GlobalExceptionHandler la mapea a 422 con el code de la regla en el
# ProblemDetail. NO se hace INSERT, no hay fila en la base.
#
# 422 vs 400: 400 significa que la request está mal formada (falta un
# campo, tipo incorrecto). 422 significa que está bien formada pero
# viola una regla que el usuario final controla (monto bajo el mínimo,
# límite excedido, currency no soportada).

set -u

API=http://localhost:8080/transactions
CT='Content-Type: application/json'

if [ -t 1 ]; then G='\033[0;32m'; R='\033[0;31m'; B='\033[1m'; N='\033[0m'
else G=''; R=''; B=''; N=''; fi
pass() { printf "${G}${B}✔ EXITOSA${N} — %s\n" "$1"; }
fail() { printf "${R}${B}✘ FALLIDA${N} — %s\n" "$1"; }
summary() {
  local ok=$1 total=$2
  echo
  echo "─── Resultado global ───"
  if [ "$ok" -eq "$total" ]; then
    pass "$ok/$total sub-pruebas exitosas."
  else
    fail "$ok/$total sub-pruebas exitosas — hay al menos una falla."
  fi
}

cat <<'BANNER'
============================================================
 Caso 6 — Reglas de negocio -> 422
------------------------------------------------------------
 Tres requests bien formados pero que violan reglas distintas.
 Verifica: HTTP 422 en las tres, con code descriptivo.
============================================================
BANNER

OK=0
TOTAL=0

run_case() {
  local label=$1 payload=$2 expected_code=$3
  TOTAL=$((TOTAL+1))
  echo
  echo "─── Petición: $label ───"
  echo
  echo "curl -X POST $API \\"
  echo "  -H '$CT' \\"
  echo "  -d '$payload'"
  echo
  STATUS=$(curl -s -o /tmp/case06-body.json -w '%{http_code}' -X POST "$API" -H "$CT" -d "$payload")
  CODE=$(jq -r .code /tmp/case06-body.json)
  echo "→ HTTP $STATUS"
  jq '{status,detail,code}' /tmp/case06-body.json
  if [ "$STATUS" = "422" ] && [ "$CODE" = "$expected_code" ]; then
    pass "422 + code=$expected_code."
    OK=$((OK+1))
  else
    fail "esperado 422 + code=$expected_code, obtenido HTTP $STATUS + code=$CODE."
  fi
}

run_case "DEBIT sobre el límite" \
  '{"accountId":"a","type":"DEBIT","amount":50000,"currency":"MXN"}' \
  "DEBIT_LIMIT_EXCEEDED"

run_case "amount bajo el mínimo" \
  '{"accountId":"a","type":"CREDIT","amount":0.50,"currency":"MXN"}' \
  "AMOUNT_BELOW_MINIMUM"

run_case "currency no soportada" \
  '{"accountId":"a","type":"CREDIT","amount":100,"currency":"USD"}' \
  "UNSUPPORTED_CURRENCY"

summary "$OK" "$TOTAL"
