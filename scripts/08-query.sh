#!/bin/bash
# Caso 8 — Consulta paginada
# ------------------------------------------------------------
# GET /transactions con filtros opcionales (accountId, status, type) y
# paginación (page, limit con defaults 0/20).
#
# La paginación NO usa COUNT(*). El service pide limit+1 filas al repo
# para deducir hasNext sin escanear la tabla entera. Se recorta a limit
# antes de responder.

set -u

API=http://localhost:8080/transactions

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
 Caso 8 — GET /transactions con filtros y paginación
------------------------------------------------------------
 Ver que el shape del response es { items, page, limit, hasNext }
 y que los filtros efectivamente restringen resultados.
============================================================
BANNER

OK=0
TOTAL=0

echo
echo "─── Petición: primera página con limit chico para forzar hasNext ───"
echo
echo "curl \"$API?limit=3\""
echo
BODY=$(curl -s "$API?limit=3")
echo "→ shape del response:"
echo "$BODY" | jq '{count:(.items|length),page,limit,hasNext}'
TOTAL=$((TOTAL+1))
# Valida que el shape del response tenga las 4 claves esperadas.
if echo "$BODY" | jq -e '.items and (.page != null) and .limit and (.hasNext != null)' >/dev/null; then
  pass "response tiene items, page, limit y hasNext."
  OK=$((OK+1))
else
  fail "shape del response incompleto — falta alguna clave."
fi

echo
echo "─── Petición: filtrar por status=REJECTED ───"
echo
echo "curl \"$API?status=REJECTED&limit=5\""
echo
BODY=$(curl -s "$API?status=REJECTED&limit=5")
echo "→ statuses distintos que vinieron:"
echo "$BODY" | jq '[.items[].status]|unique'
TOTAL=$((TOTAL+1))
# El filtro debería devolver 0 items o solo items REJECTED.
if echo "$BODY" | jq -e '[.items[].status] | all(. == "REJECTED")' >/dev/null; then
  pass "todos los items respetan el filtro status=REJECTED (o no vinieron items)."
  OK=$((OK+1))
else
  fail "el filtro devolvió statuses distintos a REJECTED."
fi

echo
echo "─── Petición: paginación explícita (page=0, limit=2) ───"
echo
echo "curl \"$API?status=EXECUTED&limit=2&page=0\""
echo
BODY=$(curl -s "$API?status=EXECUTED&limit=2&page=0")
echo "→ $(echo "$BODY" | jq '{ids:[.items[].id],page,limit,hasNext}')"
TOTAL=$((TOTAL+1))
# page debe ser 0 y limit 2 en el eco del response.
if [ "$(echo "$BODY" | jq -r '.page')" = "0" ] && [ "$(echo "$BODY" | jq -r '.limit')" = "2" ]; then
  pass "el response refleja page=0, limit=2 tal como se pidieron."
  OK=$((OK+1))
else
  fail "el response no refleja los parámetros pedidos."
fi

summary "$OK" "$TOTAL"
