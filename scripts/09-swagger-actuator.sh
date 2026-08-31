#!/bin/bash
# Caso 9 — Swagger UI y Actuator
# ------------------------------------------------------------
# Verifica que las interfaces de observabilidad/documentación estén
# levantadas:
#   - /swagger-ui         → HTML de la UI (springdoc-openapi)
#   - /v3/api-docs        → OpenAPI 3 spec en JSON
#   - /actuator/health    → estado de la app (incluye subprobes de K8s)

set -u

BASE=http://localhost:8080

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
 Caso 9 — Endpoints de documentación y health
------------------------------------------------------------
 Verifica que Swagger UI, OpenAPI spec y Actuator health respondan.
============================================================
BANNER

OK=0
TOTAL=0

check() {
  local label=$1 path=$2 expected=${3:-200}
  TOTAL=$((TOTAL+1))
  echo
  echo "─── Petición: $label ───"
  echo
  echo "curl \"$BASE$path\""
  echo
  CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$path")
  echo "→ HTTP $CODE"
  if [ "$CODE" = "$expected" ]; then
    pass "HTTP $CODE (esperado $expected)."
    OK=$((OK+1))
  else
    fail "esperaba $expected, obtuvo $CODE."
  fi
}

check "Swagger UI (springdoc redirige internamente a /swagger-ui/index.html)" \
  "/swagger-ui"

check "OpenAPI spec en JSON" \
  "/v3/api-docs"

check "Health general" \
  "/actuator/health"

check "Liveness probe (para Kubernetes)" \
  "/actuator/health/liveness"

check "Readiness probe (para Kubernetes)" \
  "/actuator/health/readiness"

summary "$OK" "$TOTAL"
