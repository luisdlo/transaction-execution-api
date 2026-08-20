CREATE TABLE transactions (
    id                      UUID            PRIMARY KEY,
    account_id              VARCHAR(64)     NOT NULL,
    type                    VARCHAR(16)     NOT NULL,
    amount                  NUMERIC(19, 4)  NOT NULL,
    currency                CHAR(3)         NOT NULL,
    description             VARCHAR(255),

    status                  VARCHAR(16)     NOT NULL,

    provider_transaction_id VARCHAR(64),
    balance_after           NUMERIC(19, 4),

    failure_code            VARCHAR(64),
    failure_message         VARCHAR(512),

    idempotency_key         VARCHAR(128),

    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,

    CONSTRAINT ck_transactions_type
        CHECK (type IN ('CREDIT', 'DEBIT')),

    CONSTRAINT ck_transactions_status
        CHECK (status IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED')),

    CONSTRAINT ck_transactions_amount_positive
        CHECK (amount > 0),

    CONSTRAINT ck_transactions_currency
        CHECK (currency = 'MXN')
);


-- Idempotencia: dos requests con la misma llave sobre la misma cuenta no pueden
-- generar dos transacciones. Indice parcial porque la llave es opcional:
-- solo indexamos las filas que la traen, evitando entradas nulas.
CREATE UNIQUE INDEX ux_transactions_account_idempotency_key
    ON transactions (account_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;


-- Query principal del GET /transactions. El orden de columnas sigue el orden de la
-- clausula: filtro por igualdad primero, columna de ordenamiento despues.
-- Incluye id como desempate para que la paginacion sea estable.
CREATE INDEX ix_transactions_account_created_at
    ON transactions (account_id, created_at DESC, id DESC);


-- Soporta el listado sin accountId y los filtros por status/type.
CREATE INDEX ix_transactions_created_at
    ON transactions (created_at DESC, id DESC);


-- Reconciliacion: transacciones que quedaron PENDING porque el servicio murio
-- entre la llamada al proveedor y la persistencia del resultado. Indice parcial
-- porque en operacion normal esta poblacion es minima frente al total de filas.
CREATE INDEX ix_transactions_pending
    ON transactions (created_at)
    WHERE status = 'PENDING';


COMMENT ON TABLE transactions IS
    'Ledger de transacciones. Append-only salvo la transicion de estado PENDING -> final.';

COMMENT ON COLUMN transactions.status IS
    'PENDING: persistida antes de llamar al proveedor. '
    'EXECUTED: el proveedor aprobo. '
    'REJECTED: el proveedor rechazo explicitamente (respuesta conocida). '
    'FAILED: resultado desconocido (timeout, 5xx, circuit breaker abierto).';

COMMENT ON COLUMN transactions.balance_after IS
    'Saldo devuelto por el proveedor. Nulo mientras la transaccion no este EXECUTED.';
