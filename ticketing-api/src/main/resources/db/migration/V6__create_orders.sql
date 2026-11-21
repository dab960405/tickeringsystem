CREATE TABLE orders (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID           NOT NULL REFERENCES users (id),
    reservation_id  UUID           NOT NULL REFERENCES reservations (id),
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING_PAYMENT',
    total_amount    NUMERIC(12, 2) NOT NULL,
    currency        CHAR(3)        NOT NULL DEFAULT 'USD',
    idempotency_key VARCHAR(100)   NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED', 'REFUNDED')),
    CONSTRAINT ck_orders_total CHECK (total_amount >= 0),
    CONSTRAINT uq_orders_user_idempotency_key UNIQUE (user_id, idempotency_key)
);

CREATE INDEX ix_orders_user_id_created_at ON orders (user_id, created_at DESC);
