ALTER TABLE orders
    ADD COLUMN fulfillment_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN refund_status VARCHAR(30) NOT NULL DEFAULT 'NONE';

UPDATE orders o
SET fulfillment_status = CASE
    WHEN EXISTS (
        SELECT 1
        FROM order_items oi
        JOIN tickets t ON t.order_item_id = oi.id
        WHERE oi.order_id = o.id
    ) THEN 'FULFILLED'
    ELSE 'PENDING'
END,
refund_status = CASE
    WHEN o.status = 'REFUNDED' THEN 'PENDING_RECONCILIATION'
    ELSE 'NONE'
END;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_fulfillment_status
        CHECK (fulfillment_status IN ('PENDING', 'FULFILLED', 'UNFULFILLABLE')),
    ADD CONSTRAINT ck_orders_refund_status
        CHECK (refund_status IN (
            'NONE', 'REQUIRED', 'REQUESTED', 'PENDING_RECONCILIATION', 'FAILED', 'CONFIRMED'
        ));

ALTER TABLE payments
    ADD COLUMN refund_provider_reference VARCHAR(100),
    ADD CONSTRAINT uq_payments_refund_provider_reference UNIQUE (refund_provider_reference);
