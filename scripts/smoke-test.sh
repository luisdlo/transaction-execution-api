#!/bin/bash
API=http://localhost:8080/transactions
WM=http://localhost:8081/__admin
H='Content-Type: application/json'

echo "=== 1. Aprobado -> 201 EXECUTED"
curl -s -i -X POST $API -H "$H" \
  -d '{"accountId":"acc-ok","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"Test"}' | head -1
curl -s -X POST $API -H "$H" \
  -d '{"accountId":"acc-ok","type":"CREDIT","amount":1500.00,"currency":"MXN"}' | jq '{status,providerTransactionId,balanceAfter}'

echo "=== 2. Rechazo del proveedor -> 201 REJECTED (NO 4xx)"
curl -s -X POST $API -H "$H" \
  -d '{"accountId":"acc-fail","type":"DEBIT","amount":100,"currency":"MXN"}' | jq '{status,failureCode}'

echo "=== 3. Read timeout -> 201 FAILED, y SOLO 1 request al proveedor"
BEFORE=$(curl -s $WM/requests/count | jq .count)
time curl -s -X POST $API -H "$H" \
  -d '{"accountId":"acc-slow","type":"CREDIT","amount":100,"currency":"MXN"}' | jq '{status,failureMessage}'
AFTER=$(curl -s $WM/requests/count | jq .count)
echo "requests al proveedor: $((AFTER-BEFORE))  <- DEBE SER 1, y el tiempo ~3s no ~8s"

echo "=== 4. Idempotencia: dos veces, MISMO id"
curl -s -X POST $API -H "$H" -H 'Idempotency-Key: k-001' \
  -d '{"accountId":"acc-idem","type":"CREDIT","amount":200,"currency":"MXN"}' | jq -r .id
curl -s -X POST $API -H "$H" -H 'Idempotency-Key: k-001' \
  -d '{"accountId":"acc-idem","type":"CREDIT","amount":200,"currency":"MXN"}' | jq -r .id

echo "=== 5. Currency minúscula -> 201 con MXN"
curl -s -X POST $API -H "$H" \
  -d '{"accountId":"acc-ok","type":"CREDIT","amount":100,"currency":"mxn"}' | jq '{status,currency}'

echo "=== 6. Reglas de negocio -> 422"
curl -s -X POST $API -H "$H" -d '{"accountId":"a","type":"DEBIT","amount":50000,"currency":"MXN"}' | jq '{status,code}'
curl -s -X POST $API -H "$H" -d '{"accountId":"a","type":"CREDIT","amount":0.50,"currency":"MXN"}' | jq '{status,code}'
curl -s -X POST $API -H "$H" -d '{"accountId":"a","type":"CREDIT","amount":100,"currency":"USD"}' | jq '{status,code}'

echo "=== 7. Errores estructurales -> 400"
curl -s -o /dev/null -w "amount negativo: %{http_code}\n" -X POST $API -H "$H" \
  -d '{"accountId":"a","type":"CREDIT","amount":-10,"currency":"MXN"}'
curl -s -o /dev/null -w "enum invalido:   %{http_code}\n" -X POST $API -H "$H" \
  -d '{"accountId":"a","type":"NOPE","amount":100,"currency":"MXN"}'
curl -s -o /dev/null -w "json roto:       %{http_code}\n" -X POST $API -H "$H" -d '{roto'
curl -s -o /dev/null -w "?status=NOPE:    %{http_code}\n" "$API?status=NOPE"
curl -s -o /dev/null -w "?limit=500:      %{http_code}\n" "$API?limit=500"

echo "=== 8. Consulta"
curl -s "$API?limit=3" | jq '{count:(.items|length),page,limit,hasNext}'
curl -s "$API?status=REJECTED&limit=5" | jq '[.items[].status]|unique'

echo "=== 9. Swagger y actuator"
curl -s -o /dev/null -w "swagger-ui: %{http_code}\n" http://localhost:8080/swagger-ui
curl -s -o /dev/null -w "api-docs:   %{http_code}\n" http://localhost:8080/v3/api-docs
curl -s -o /dev/null -w "health:     %{http_code}\n" http://localhost:8080/actuator/health

echo "=== 10. CIRCUIT BREAKER (va al final: deja el circuito abierto 10s)"
for i in 1 2 3 4; do
  curl -s -o /dev/null -X POST $API -H "$H" \
    -d '{"accountId":"acc-error","type":"CREDIT","amount":100,"currency":"MXN"}'
done
B=$(curl -s $WM/requests/count | jq .count)
curl -s -X POST $API -H "$H" \
  -d '{"accountId":"acc-error","type":"CREDIT","amount":100,"currency":"MXN"}' | jq -r .failureMessage
A=$(curl -s $WM/requests/count | jq .count)
echo "requests con circuito abierto: $((A-B))  <- DEBE SER 0"