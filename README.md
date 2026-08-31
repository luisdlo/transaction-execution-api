# Transaction Execution API

API REST que ejecuta transacciones financieras (CREDIT/DEBIT) contra un proveedor
externo, persiste el resultado con la máquina de estados
`PENDING → EXECUTED | REJECTED | FAILED`, y expone consulta paginada con filtros.
Stack: Java 21 + Spring Boot 4.1 + PostgreSQL 16.

## Cómo levantarlo

Prerrequisitos: Docker. Para desarrollo local también JDK 21 (el wrapper
`./mvnw` viene incluido).

### Opción 1 — Todo en Docker

```bash
docker compose up --build
```

Levanta Postgres 16, WireMock (proveedor externo simulado) **y la app** en
contenedores. La imagen de la app se construye desde el `Dockerfile` con
`spring-boot-maven-plugin`, corre como usuario no-root, y respeta el memory
limit del contenedor con `-XX:MaxRAMPercentage=75.0` (no hardcodea `-Xmx`,
así escala solo si mañana subes el limit del pod). Puerto expuesto: `8080`.

### Opción 2 — Dependencias en Docker, app en local (dev loop)

```bash
# Sólo dependencias — hay que nombrar los servicios, un `up -d` a secas
# levantaría también `app` y chocaría con el 8080 del `mvn spring-boot:run`.
docker compose up -d postgres wiremock

# App en local con reload rápido
./mvnw spring-boot:run
```

Es el flujo típico durante desarrollo: cambio en Java → `mvn spring-boot:run`
recompila y arranca en segundos, mientras Postgres y WireMock siguen up en
segundo plano.

### Tests y smoke

```bash
# Suite completa: unitarios + integración con Testcontainers + WireMock in-process
./mvnw clean install

# Smoke tests end-to-end contra una app ya levantada (Opción 1 u Opción 2):
./scripts/smoke-test.sh                # todos los casos en un solo run
./scripts/01-approved.sh               # o un caso a la vez, 01..10
```

### Ciclo de vida de los contenedores

```bash
# Ver qué está corriendo
docker compose ps

# Logs (todos los servicios, o uno específico)
docker compose logs -f              # todos, follow
docker compose logs -f app          # sólo la app
docker compose logs --tail=100 app  # últimas 100 líneas

# Parar SIN borrar los contenedores (conserva estado, arranca rápido con `up`)
docker compose stop

# Bajar TODO (contenedores + red). Conserva el volumen de Postgres.
docker compose down

# Bajar TODO INCLUYENDO datos de Postgres. Útil para empezar de cero.
docker compose down -v

# --- Operar POR SERVICIO (subconjunto de contenedores) --------------------
# Detener SÓLO la app; postgres y wiremock siguen corriendo.
docker compose stop app

# Volver a arrancarla sin rebuild (el contenedor sigue creado, sólo parado)
docker compose start app

# Levantar SÓLO postgres + wiremock (dev loop: la app la corres con ./mvnw).
# Hay que nombrar los servicios — un `up -d` a secas incluiría también la app.
docker compose up -d postgres wiremock

# Detener + BORRAR el contenedor de la app (libera el 8080 del host).
# Útil justo antes de correr `./mvnw spring-boot:run` en local.
docker compose rm -sf app
# --------------------------------------------------------------------------

# Reiniciar sólo la app (sin tocar postgres/wiremock)
docker compose restart app

# Reconstruir la imagen de la app después de un cambio en Java o pom.xml
docker compose build app           # sólo reconstruye, no arranca
docker compose up -d --build app   # reconstruye y arranca (recreando el container)

# Reconstruir SIN caché (fuerza a redescargar dependencias, más lento)
docker compose build --no-cache app

# Borrar la imagen de la app del daemon local (libera espacio)
docker rmi transaction-execution-api-app

# Limpieza agresiva: contenedores parados + imágenes huérfanas + build cache
docker system prune -f
```

Regla mental: durante desarrollo con `docker compose up --build`, sólo cambios en
`pom.xml` o `src/` invalidan las capas de la imagen. Cambios en otros archivos
(README, scripts, etc.) reutilizan cache y el rebuild es de segundos.

Puertos por defecto:

| Recurso            | URL                                        |
|--------------------|--------------------------------------------|
| API                | `http://localhost:8080/transactions`       |
| Swagger UI         | `http://localhost:8080/swagger-ui`         |
| OpenAPI spec       | `http://localhost:8080/v3/api-docs`        |
| Health (K8s probes)| `http://localhost:8080/actuator/health`    |

### Documentación del código

Tres capas de documentación conviven en el repo, cada una con un propósito
distinto:

| Fuente | Para quién | Cómo verla |
|---|---|---|
| **OpenAPI / Swagger UI** | Consumidores del API | `http://localhost:8080/swagger-ui` con la app arriba |
| **Javadoc** en las clases del contrato | Quien lee/mantiene el código | Directamente en el archivo, o `./mvnw javadoc:javadoc` para generar el sitio en `target/reports/apidocs/` |
| **Comentarios `//` inline** | Quien revisa una decisión concreta | En el archivo, junto al código que explican |

Los seis puntos de entrada donde el Javadoc te da el contrato completo (invariantes,
excepciones, semántica de la máquina de estados):

- `service/TransactionService` — orquestación y garantías del método `execute`.
- `service/rule/TransactionRule` — cómo se añaden reglas, qué garantías tienen.
- `repository/TransactionRepository` — semántica de `save` con idempotencia y del guard
  atómico de los `markX`.
- `model/Transaction` — la máquina de estados y qué campos son válidos por status.
- `controller/TransactionController` — tabla completa de códigos HTTP y por qué.
- `dto/request/CreateTransactionRequest` + `dto/response/TransactionResponse` — contrato
  público, por qué está separado del modelo persistido.

El resto del código sigue la regla del `CLAUDE.md`: **comentar el *por qué*, nunca el
*qué***. Los métodos no obvios tienen comentarios `//` inline explicando decisiones
(por ejemplo por qué `DefaultTransactionService.execute` no lleva `@Transactional`),
en vez de Javadoc que repita el nombre del método.

#### Generar el sitio Javadoc

```bash
# Genera el sitio HTML en target/reports/apidocs/
./mvnw javadoc:javadoc

# Abrirlo en el navegador
open target/reports/apidocs/index.html          # macOS
xdg-open target/reports/apidocs/index.html      # Linux
start target/reports/apidocs/index.html         # Windows

# Servirlo en un puerto local (útil si file:// te da problemas)
cd target/reports/apidocs && python3 -m http.server 9000
# → http://localhost:9000

# Empaquetarlo en un jar (para deploy a un repo Maven)
./mvnw javadoc:jar
# → target/transaction-execution-api-0.0.1-SNAPSHOT-javadoc.jar
```

El `pom.xml` configura `maven-javadoc-plugin` con `<doclint>all,-missing</doclint>` —
silencia sólo los warnings de "missing @param" (natural en records con nombres de
campo auto-descriptivos), sin apagar los demás lints (referencias rotas, HTML mal
formado). Ver el comentario en el pom para el porqué.

### Ejecutar una transacción

```bash
curl -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: req-4f2c-0001' \
  -d '{
    "accountId": "acc-1234",
    "type": "CREDIT",
    "amount": 1500.00,
    "currency": "MXN",
    "description": "Depósito de prueba"
  }'
# 201 Created, status: EXECUTED, providerTransactionId, balanceAfter
```

### Los cuatro escenarios de WireMock

El stub del proveedor selecciona su comportamiento con base en `accountId`:

```bash
# 1) Aprobado — cualquier accountId no listado abajo
curl -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-xyz","type":"CREDIT","amount":100,"currency":"MXN"}'
# 201 Created, status: EXECUTED

# 2) Rechazo de negocio — 400 INSUFFICIENT_FUNDS del proveedor
curl -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-fail","type":"DEBIT","amount":100,"currency":"MXN"}'
# 201 Created, status: REJECTED, failureCode: INSUFFICIENT_FUNDS

# 3) Proveedor caído — 503 con retry (2 reintentos, 3 requests totales)
curl -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-error","type":"CREDIT","amount":100,"currency":"MXN"}'
# 201 Created, status: FAILED — provider unavailable

# 4) Read timeout — la petición sale, el cargo pudo ejecutarse, requiere reconciliación
curl -X POST http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"acc-slow","type":"CREDIT","amount":100,"currency":"MXN"}'
# 201 Created, status: FAILED — unknown state
```

En los cuatro casos la respuesta HTTP es **201**. Ver
["201 en los tres estados finales"](#201-en-los-tres-estados-finales).

### Consultar con filtros y paginación

```bash
curl 'http://localhost:8080/transactions?accountId=acc-1234&status=EXECUTED&page=0&limit=20'
# 200 OK — { items: [...], page: 0, limit: 20, hasNext: true|false }
```

## Stack y por qué

- **Java 21** — virtual threads habilitados (`spring.threads.virtual.enabled=true`).
  El servicio es I/O-bound: una llamada HTTP bloqueante por transacción. Con
  platform threads el pool satura antes de los 200 concurrentes.
- **Spring Boot 4.1 / Spring Framework 7** — `@Retryable` nativo vía
  `@EnableResilientMethods` sin depender de Spring Retry.
- **PostgreSQL 16 + Flyway** — un ledger financiero exige integridad
  transaccional, índices únicos (parciales para idempotencia) y CHECK
  constraints. Una base documental o key-value mueve esas garantías al código,
  donde se rompen. Flyway es dueño único del esquema; la app no lo altera en
  runtime.
- **Spring JDBC (`JdbcClient`) — SIN JPA.** El CRUD generado por JPA cubriría
  quizá el 25% de las operaciones. El otro 75% (filtros dinámicos con
  predicados condicionales, update condicional por estado, resolución de
  idempotencia atrapando `DuplicateKeyException`) ya obliga a SQL explícito.
  Sumar JPA sería agregar magia y un ORM para reemplazar tres statements.
- **WireMock** — único modo de probar la serialización, mapeo de errores,
  retry y timeouts del cliente HTTP contra tráfico real.
- **Testcontainers** — Postgres real en los tests de integración. H2 en
  memoria no ejerce ni el CHECK constraint ni el índice único parcial, que
  son parte del contrato del repositorio.

## Decisiones de diseño

### Write-ahead
La fila `PENDING` se persiste y se **commitea antes** de llamar al proveedor.
Si el proceso muere entre pasos, la fila queda en `PENDING` y es reconciliable.
La secuencia inversa (llamar primero, persistir después) deja un cargo real sin
registro local si el proceso cae en medio.

### El service NO es `@Transactional`
Deliberado. `@Transactional` en `execute()` mantendría una conexión de BD
abierta durante la llamada HTTP externa. A millones de transacciones diarias
eso agota el connection pool en minutos. Cada operación de persistencia corre
como su propia transacción corta auto-commit — es lo que hace posible el
write-ahead.

### Circuit breaker programático (no `@CircuitBreaker`)
Resilience4j core, decorado explícitamente dentro de `execute()`:

```
try {
    return circuitBreaker.executeSupplier(() -> doExecute(transaction));
} catch (CallNotPermittedException e) {
    throw new ProviderUnavailableException("PROVIDER_CIRCUIT_OPEN", ...);
}
```

Se hace programático **y no** con `@CircuitBreaker` para que el orden
`@Retryable` (afuera) → circuit breaker (adentro) sea explícito en el código
y no dependa del orden con que Spring apile los proxies AOP. Cada reintento
consulta el breaker; una vez abierto, las siguientes llamadas devuelven
`CallNotPermittedException` sin tocar la red — se traduce a
`ProviderUnavailableException` con code `PROVIDER_CIRCUIT_OPEN` para que
ningún consumidor fuera del adaptador tenga que saber que existe un breaker.

Configuración: ventana COUNT_BASED de 20, mínimo 10 llamadas para evaluar,
umbral de falla 50%, 10s en estado OPEN, 3 llamadas permitidas en HALF_OPEN.

`recordExceptions`: `ProviderUnavailableException`, `ProviderUnknownStateException`.
`ignoreExceptions`: `ProviderRejectedException` — un rechazo de negocio es
respuesta válida del proveedor, no una falla del transporte; contarlo abriría
el breaker durante una avalancha de saldos insuficientes.

### Retry solo en 5xx / 429
- **4xx** — determinista. Reintentar no puede producir una respuesta distinta y
  podría duplicar un cargo si el proveedor cambia de opinión. Nunca se reintenta.
- **5xx / 429** — el proveedor pide reintento explícito o su lado falló.
  `@Retryable` con backoff (2 reintentos → 3 requests totales por defecto).
- **429 va evaluado ANTES que la rama de 4xx.** Un 429 es un 4xx pero
  semánticamente es "reintentá luego"; caer en la rama de rechazo de negocio
  lo convertiría en `ProviderRejectedException` que nunca se reintenta.

### Connect timeout ≠ read timeout
La decisión más importante del cliente HTTP:

- **Connect timeout / `ConnectException`** — la petición **nunca salió** del
  proceso. Seguro reintentar. Mapea a `ProviderUnavailableException`.
- **Read timeout / `SocketTimeoutException`** — la petición **salió**, la
  respuesta no volvió. El cargo **pudo haberse ejecutado**. Reintentar
  duplicaría dinero real. Mapea a `ProviderUnknownStateException` → `FAILED`
  para reconciliación manual.

Confundir estas dos categorías es cómo se duplican cargos en producción.

### Idempotencia sin check-then-act
Header opcional `Idempotency-Key`. El esquema tiene un índice único parcial
`(account_id, idempotency_key) WHERE idempotency_key IS NOT NULL`. Al insertar,
se deja que la BD arbitre la carrera: si otra request con la misma llave ya
ganó, `save()` atrapa `DuplicateKeyException` y devuelve la fila existente.
No hay `findByIdempotencyKey` seguido de `insert` — esa secuencia es
exactamente la carrera que el patrón evita.

### Update condicional en vez de optimistic locking
```
UPDATE transactions SET status = 'EXECUTED', ...
 WHERE id = :id AND status = 'PENDING'
```
Se verifica `rowsAffected == 1`. Si otro actor ya movió la fila fuera de
`PENDING`, la actualización afecta 0 filas y el repositorio lanza
`ConcurrentTransactionUpdateException`. Un guard atómico sin columna `version`
que mantener.

### Paginación sin `COUNT(*)`
Se pide `limit + 1` filas; si vuelve la fila extra, `hasNext = true` y se
recorta a `limit` antes de responder. No se expone `total` — un `COUNT(*)`
sobre una tabla de millones, sin filtros que usen índice, es una consulta cara
que el cliente no está pidiendo.

### 201 en los tres estados finales
El `POST` devuelve **201 Created** en `EXECUTED`, `REJECTED` **y** `FAILED`.
Un rechazo del proveedor no es error HTTP: la transacción fue creada y está
persistida. El cliente inspecciona `status` en el body para saber qué pasó.
Reservar 4xx para fallas HTTP-shape mantiene honestos los reintentos
idempotentes y los dashboards de monitoreo.

### 422 (regla de negocio) vs 400 (estructura)
Todas las respuestas de error son `ProblemDetail` (RFC 7807).

| HTTP | Causa                                                             |
|------|-------------------------------------------------------------------|
| 400  | Request mal formada: falta campo, tipo incorrecto, enum inválido, JSON malformado. El fix vive en el código del cliente. |
| 422  | Request bien formada, viola una regla de negocio (monto bajo el mínimo, currency no soportada, límite de débito excedido). El fix vive en el input del usuario final. |
| 409  | `ConcurrentTransactionUpdateException` — la fila cambió bajo nosotros. |
| 404  | Transacción no encontrada.                                        |
| 500  | Fallo inesperado. **Nunca** se propaga el mensaje interno al cliente. |

## Qué NO se hizo y por qué

### Kafka / eventos asíncronos
El `POST /transactions` es **síncrono por contrato**: el cliente necesita el
`status` final y el `balanceAfter` en la respuesta. Kafka no reemplaza el
síncrono; iría **encima** como outbox pattern (una tabla `transaction_events`
alimentada por el mismo flujo, publicada por un poller separado). Fuera de
alcance en 3 días.

### Job de reconciliación PENDING / FAILED
El esquema tiene un índice parcial que soporta la búsqueda barata de las filas
no terminales, esperando un scheduled job que las resuelva contra el proveedor.
La estructura está lista — el job no.

### CI pipeline
Sin `.github/workflows/`. `./mvnw clean install` cubre la validación local
(78 tests, todos verdes). Un workflow de GitHub Actions es trivial de agregar;
se dejó fuera por foco de tiempo.

## Relación con el stack de Spin

- **Java** — Java 21 con virtual threads.
- **PostgreSQL** — motor principal, esquema versionado con Flyway.
- **Microservicios** — el servicio es autocontenido; se comunica hacia afuera
  vía HTTP síncrono (proveedor) y expone HTTP síncrono (clientes internos).
- **Kubernetes** — `server.shutdown: graceful` para terminar los requests en
  vuelo durante rolling updates, y probes de liveness/readiness expuestas en
  `/actuator/health/liveness` y `/actuator/health/readiness`.

## Uso de IA

Se usó **Claude Code** (Anthropic) como herramienta de generación asistida
durante toda la implementación. Alcance:

- Generación de boilerplate: DTOs, mappers, esqueletos de repositorio y
  controller.
- Escritura inicial de tests unitarios y de integración.
- Documentación (este README, Javadoc, comentarios que explican el *por qué*
  de cada decisión).

**Cada archivo fue revisado y corregido manualmente**, no se acepta código a
ciegas. Las siguientes decisiones concretas salieron de la revisión, no del
generador, y quedaron en el código:

1. **Distinción connect timeout vs read timeout en `ProviderServiceImpl`.**
   La primera versión mapeaba ambos igual. Del `IOException` genérico no
   puedes decidir si duplicaste un cargo. Se separaron: la primera es
   `ProviderUnavailableException` (retry OK), la segunda es
   `ProviderUnknownStateException` (nunca retry). Ver `translateIoException`
   y la sección [Connect timeout ≠ read timeout](#connect-timeout--read-timeout).

2. **El 429 caía en la rama de 4xx.** El check original evaluaba
   `is4xxClientError()` **antes** que 429. Como 429 es un 4xx, ese branch se
   comía la respuesta y la convertía en `ProviderRejectedException` — un
   "reintentá luego" mapeado a "rechazo de negocio, no reintentar". Código
   muerto perfecto. Se movió el check de 429 al inicio de `dispatch`.

3. **Idempotencia duplicada entre service y repository.** La primera versión
   repetía el `try/catch DuplicateKeyException` en ambas capas.
   `JdbcTransactionRepository.save()` ya arbitra la carrera; el service tenía
   código muerto. Se eliminó del service — la responsabilidad vive en una
   sola capa.

4. **Overflow del offset en `find()`.** La versión original hacía
   `page * limit` como `int`. Con `page = Integer.MAX_VALUE / 50` el resultado
   desborda a negativo silenciosamente y el repositorio recibe un offset
   inválido con un error opaco. Se cambió la multiplicación a `long` y la
   firma de `TransactionRepository.findByFilters` para aceptar `long offset`
   (Postgres `OFFSET` acepta `bigint`).

5. **Circuit breaker programático en vez de `@CircuitBreaker`.** El generador
   sugirió el anotativo. Se cambió a decoración explícita
   (`circuitBreaker.executeSupplier(...)`) dentro de `execute()` para que el
   orden `@Retryable` (afuera) → circuit breaker (adentro) sea visible en el
   código, y no dependa del orden con que Spring apila los proxies AOP.
   `CallNotPermittedException` se traduce a `ProviderUnavailableException`
   para que Resilience4j no se escape del adaptador.

6. **Idempotencia rota en el caso secuencial.** En una versión intermedia el
   service llamaba al proveedor una segunda vez cuando `save()` devolvía una
   fila preexistente ya resuelta (hit idempotente del repositorio). Esto
   **duplicaba el cargo** y además hacía fallar el update condicional posterior
   con `ConcurrentTransactionUpdateException` → 409 al cliente. **Los tests
   unitarios del service no lo detectaron** porque los mocks no reproducían
   el comportamiento real de `JdbcTransactionRepository.save()`. Apareció con
   el smoke test manual (`scripts/smoke-test.sh`). Se cerró con el guard
   `if (saved.status() != PENDING) return saved;` inmediatamente después del
   save.

Estas seis son criterio de ingeniería, no output de un modelo. Es lo que
separa "generar código" de "diseñar un sistema".
