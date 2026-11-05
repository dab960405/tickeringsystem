CREATE TABLE tickets (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_item_id UUID        NOT NULL REFERENCES order_items (id),
    code          VARCHAR(64) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'VALID',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tickets_status CHECK (status IN ('VALID', 'USED', 'CANCELLED')),
    CONSTRAINT uq_tickets_code UNIQUE (code)
);

CREATE INDEX ix_tickets_order_item_id ON tickets (order_item_id);
