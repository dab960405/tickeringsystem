CREATE TABLE order_items (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id       UUID           NOT NULL REFERENCES orders (id),
    ticket_type_id UUID           NOT NULL REFERENCES ticket_types (id),
    quantity       INTEGER        NOT NULL,
    unit_price     NUMERIC(12, 2) NOT NULL,
    currency       CHAR(3)        NOT NULL DEFAULT 'USD',
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit_price CHECK (unit_price >= 0)
);

CREATE INDEX ix_order_items_order_id ON order_items (order_id);
