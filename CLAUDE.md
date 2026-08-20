# CLAUDE.md — Transaction Execution API (Spin)

Reglas del proyecto. Aplican a toda sesión de Claude Code.
Si una instrucción mía contradice este archivo, avísame antes de escribir código.

---

## Contexto

API REST que ejecuta transacciones financieras (CREDIT / DEBIT) contra un proveedor
externo, persiste el resultado y expone consulta paginada. Challenge técnico con
entrega a 3 días. El código será revisado línea por línea, me harán preguntas sobre
cada decisión y probablemente me pidan cambios en vivo.

Prioridad: código que comunica intención > cobertura de features.
El challenge premia explícitamente NO sobre-ingenierizar.

---

## Stack

| Aspecto | Decisión |
|---|---|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3.5.x |
| Persistencia | Spring JDBC (`JdbcClient`) con SQL explícito, sin ORM |
| Base de datos | PostgreSQL 16 |
| Migraciones | Flyway (dueño único del esquema) |
| Cliente HTTP | `RestClient` + Resilience4j |
| Mock del proveedor | WireMock |
| Tests | JUnit 5, Mockito, AssertJ, Testcontainers (Postgres real) |
| Modelado de datos | `record` de Java 21, mapeo manual |
| Concurrencia | Virtual threads (`spring.threads.virtual.enabled=true`) |
| Build | Maven |

---

## Arquitectura: por capas (layered / n-tier)

No es MVC — MVC es un patrón de presentación. Esto es arquitectura en capas.

```
com.spin.transactions
├── controller/     # REST: recibe, delega, mapea a DTO. Cero lógica, cero try-catch.
│   └── dto/        # contrato público del API: request entrante y response saliente
├── service/        # lógica de negocio y orquestación
│   ├── rule/       # reglas de negocio como Strategy, una clase por regla
│   └── provider/   # cliente del proveedor externo
│       └── dto/    # contrato del proveedor, que yo no controlo
├── repository/     # acceso a datos con JdbcClient + RowMapper
├── model/          # dominio: records y enums. No depende de nada.
├── exception/      # excepciones custom, todas unchecked
└── config/         # beans de configuración (Clock, RestClient, Resilience4j)
```

### Regla de dependencias

Las dependencias van hacia abajo, nunca hacia arriba:

- `controller` → `service` → `repository`
- `model` no depende de nada
- `service` **no conoce** los DTOs del controller ni clases de Spring Web
- `repository` **no conoce** al service ni al controller

Se verifica con un test de ArchUnit. Si un cambio rompe la regla, el build falla.

### Los cuatro tipos de objeto y su frontera

| Objeto | Vive en | Existe porque |
|---|---|---|
| Request del API | `controller/dto` | Contrato de entrada. Lleva Bean Validation. |
| Response del API | `controller/dto` | Contrato público de salida. Controla qué campos se exponen. |
| Command | `model` | Input ya validado al service. Sin anotaciones de Jackson. |
| Modelo de dominio | `model` | Se persiste y viaja entre capas. |
| Request/Response del proveedor | `service/provider/dto` | Contrato externo, con dueño distinto. |

Reglas duras:

- El modelo **nunca** se serializa directo como respuesta HTTP. Expondría campos
  internos (llave de idempotencia, código de falla, timestamps de auditoría).
- Los DTOs del controller **no** se reusan para hablar con el proveedor, aunque los
  campos coincidan. Son contratos con dueños distintos.
- El mapeo es manual con factory methods estáticos (`from(...)`, `toCommand()`).

### Interfaces

Llevan interfaz las clases cuya implementación es reemplazable o necesita mockearse:
service, repository, cliente del proveedor y las reglas de negocio.

El **controller no lleva interfaz**: es un adaptador terminal, nadie lo inyecta ni lo
mockea. Una interfaz ahí sería ceremonia sin beneficio.

Nomenclatura: `Repository`, no `Dao`. La interfaz habla en lenguaje de dominio
(`markExecuted`, `findByIdempotencyKey`), no de tabla (`updateStatusColumn`).

---

## Testabilidad (obligatorio desde la primera clase)

1. **Inyección por constructor siempre.**
2. **`Clock` inyectado.** Se usa `Instant.now(clock)`, nunca `Instant.now()`.
   En tests: `Clock.fixed(...)` para aserciones deterministas.
3. **Dependencias explícitas en el constructor.** Sin estáticos ni singletons ocultos.
4. **Toda clase de `service/` y `model/` debe testearse sin levantar Spring.**
   Si probar lógica de negocio requiere `@SpringBootTest`, el diseño está mal.
5. Tests de dominio y reglas: JUnit puro, milisegundos.

### Qué doble usar en cada test

| Qué se prueba | Herramienta | Por qué |
|---|---|---|
| Service | Mockito | Se mockean repository y cliente del proveedor. |
| Controller | Mockito (`@WebMvcTest`) | Se mockea el service. |
| Cliente del proveedor | WireMock | Único modo de probar serialización, mapeo de errores HTTP, retry y timeouts. |
| Repository | Testcontainers | Único modo de probar SQL real contra Postgres. |

Principio: mockear la frontera, no la cosa que se está probando.

---

## SOLID: dónde aplica aquí

- **SRP** — cada regla de negocio valida una sola cosa.
- **OCP** — agregar una regla es crear una clase; el service no se modifica.
- **DIP** — el service depende de la interfaz del repository, no de la implementación.
- **LSP / ISP** — no hay jerarquías ni interfaces gordas. No inventar casos.

---

## Convenciones de código

### Dinero
- Siempre `BigDecimal`. En BD `NUMERIC(19,4)`.
- Comparaciones con `compareTo()`, nunca `equals()`.

### Records
- Dominio y DTOs son `record`.
- Validación de invariantes en el constructor compacto.

### Estados
`PENDING → EXECUTED | REJECTED | FAILED`

- `EXECUTED` — el proveedor aprobó.
- `REJECTED` — el proveedor rechazó explícitamente (respuesta conocida).
- `FAILED` — resultado desconocido (timeout, 5xx, circuit breaker abierto).

La distinción REJECTED / FAILED es deliberada y me van a preguntar por ella.

### Nomenclatura
- Código, clases, variables, comentarios y mensajes de error: **inglés**.
- README y ADRs: **español**.
- Sin abreviaturas (`transaction`, no `txn`, salvo `providerTransactionId`).

### Estilo
- Comentar el *por qué*, nunca el *qué*. Ejemplo válido: por qué el retry excluye 4xx.
- Métodos cortos, un nivel de abstracción por método.

---

## Decisiones de diseño no negociables

### 1. Write-ahead
1. Validar reglas de negocio (antes de tocar al proveedor).
2. Persistir `PENDING` y hacer commit.
3. Llamar al proveedor.
4. Actualizar a `EXECUTED` / `REJECTED` / `FAILED`.

Si el servicio se cae a media llamada, la transacción queda `PENDING` y es reconciliable.

### 2. El método de ejecución del service no lleva `@Transactional`
Deliberado: no se mantiene una conexión de BD abierta durante una llamada HTTP externa.
A millones de transacciones diarias eso agota el connection pool.
Cada operación de persistencia es su propia transacción corta.

### 3. Idempotencia
Header `Idempotency-Key` opcional. Índice único parcial en `(account_id, idempotency_key)`.
Si llega repetida, se devuelve la transacción original con `200`, sin crear otra.

### 4. Update condicional en vez de optimistic locking
```sql
UPDATE transactions SET status = 'EXECUTED', ...
 WHERE id = :id AND status = 'PENDING'
```
Se verifica `rowsAffected == 1`. Guard de concurrencia atómico.

### 5. Retry solo en 5xx y timeouts de conexión
Nunca en 4xx ni en respuestas ambiguas: reintentar una transacción que sí se ejecutó
es duplicar dinero. Esto se comenta en el código.

### 6. Paginación sin `COUNT(*)`
Se pide `limit + 1` para saber si hay siguiente. Se expone `hasNext`, no `total`.

### 7. Filtros dinámicos
Se arma el SQL con solo los predicados que llegaron, para que Postgres use el índice
`(account_id, created_at DESC)`.

### 8. Errores en formato RFC 7807
`ProblemDetail` de Spring 6.

### 9. Manejo de excepciones
- La traducción excepción → respuesta HTTP vive centralizada en `@RestControllerAdvice`.
  Es el único lugar que conoce códigos de estado.
- **El controller no lleva try-catch. Nunca.**
- El service lleva try-catch **solo** en el write-ahead: el rechazo y la falla del
  proveedor son transiciones de la máquina de estados, no errores. Si burbujearan al
  advice, la transacción quedaría `PENDING` para siempre.
- El cliente del proveedor traduce excepciones de infraestructura a excepciones de
  dominio, para que el service no conozca clases de Spring Web.
- El repository no atrapa nada: Spring ya traduce `SQLException` a `DataAccessException`.
  Única excepción: `DuplicateKeyException` en el insert, que resuelve la carrera de
  idempotencia y es flujo de negocio.
- Todas las excepciones custom son unchecked. Sin checked exceptions.
- El handler genérico loguea el stack trace pero **nunca** expone el mensaje interno
  al cliente.

Regla que resume todo: **try-catch solo cuando la excepción cambia el flujo de negocio.
Si solo se traduce a una respuesta HTTP, sube al advice.**

---

## Flujo de trabajo

- **Plan Mode por capa**, no por proyecto. Una sesión para el modelo, otra para el
  repository, otra para el cliente del proveedor. Sesiones chicas = código que reviso
  de verdad.
- Los tests de las reglas de negocio los escribo yo a mano. No generarlos.
- Antes de escribir código: proponer el plan y esperar confirmación.
- Un commit por unidad lógica, Conventional Commits. El historial se revisa.

## Definición de "listo" para cualquier clase

- [ ] Inyección por constructor
- [ ] Sin `Instant.now()` sin `Clock`
- [ ] Tiene test unitario sin Spring (si es de `service/` o `model/`)
- [ ] No viola la regla de dependencias entre capas
- [ ] Nombres en inglés, sin abreviaturas
- [ ] `BigDecimal` para dinero
- [ ] Sin try-catch salvo que la excepción cambie el flujo de negocio
