-- Idempotency cold-path store
CREATE TABLE IF NOT EXISTS idempotency_records (
    id               BIGSERIAL PRIMARY KEY,
    idempotency_key  VARCHAR(255) NOT NULL UNIQUE,
    status           VARCHAR(20)  NOT NULL,
    response_status  INTEGER      NOT NULL DEFAULT 0,
    response_body    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_idempotency_key ON idempotency_records (idempotency_key);
COMMENT ON TABLE idempotency_records IS
    'Persistent cold-path store for idempotency check-and-set mechanism';
