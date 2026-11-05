CREATE TABLE payments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id           UUID           NOT NULL REFERENCES orders (id),
    status             VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    provider_reference VARCHAR(100),
    amount             NUMERIC(12, 2) NOT NULL,
    currency           CHAR(3)        NOT NULL DEFAULT 'USD',
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_payments_amount CHECK (amount >= 0),
    CONSTRAINT uq_payments_order_id UNIQUE (order_id),
    CONSTRAINT uq_payments_provider_reference UNIQUE (provider_reference)
);
