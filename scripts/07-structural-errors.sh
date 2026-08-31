#!/bin/bash
# Caso 7 — Errores estructurales (400 Bad Request)
# ------------------------------------------------------------
# Cinco requests estructuralmente inválidas. El fix vive en el código
# del cliente, no en el input del usuario final — por eso son 400 y no 422.
#
# Cubrimos los handlers del GlobalExceptionHandler:
#   - MethodArgumentNotValidException  (Bean Validation @Positive, @NotBlank)
#   - HttpMessageNotReadableException  (enum inválido dentro del body,
#                                       o JSON malformado)
#   - MethodArgumentTypeMismatchException (query param no convertible a enum)
#   - IllegalArgumentException del service (limit fuera de rango)

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
 Caso 7 — Errores estructurales -> 400
------------------------------------------------------------
 Cinco variantes, cada una prueba un handler distinto del advice.
============================================================
BANNER

OK=0
TOTAL=0

verdict() {
  local code=$1
  TOTAL=$((TOTAL+1))
  if [ "$code" = "400" ]; then
    pass "HTTP 400 correcto."
    OK=$((OK+1))
  else
    fail "esperaba HTTP 400, obtuvo $code."
  fi
}

check_post() {
  local label=$1 payload=$2
  echo
  echo "─── Petición: $label ───"
  echo
  echo "curl -X POST $API \\"
  echo "  -H '$CT' \\"
  echo "  -d '$payload'"
  echo
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API" -H "$CT" -d "$payload")
  echo "→ HTTP $CODE"
  verdict "$CODE"
}

check_get() {
  local label=$1 url=$2
  echo
  echo "─── Petición: $label ───"
  echo
  echo "curl \"$url\""
  echo
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "$url")
  echo "→ HTTP $CODE"
  verdict "$CODE"
}

check_post "amount negativo (@Positive de Bean Validation)" \
  '{"accountId":"a","type":"CREDIT","amount":-10,"currency":"MXN"}'

check_post "enum inválido en el body (Jackson deserialize fail)" \
  '{"accountId":"a","type":"NOPE","amount":100,"currency":"MXN"}'

check_post "JSON roto (parse error)" \
  '{roto'

check_get "query param no convertible a enum" \
  "$API?status=NOPE"

check_get "limit fuera de rango (IllegalArgumentException)" \
  "$API?limit=500"

summary "$OK" "$TOTAL"
