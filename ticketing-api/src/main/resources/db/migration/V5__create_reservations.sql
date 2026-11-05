CREATE TABLE reservations (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID        NOT NULL REFERENCES users (id),
    ticket_type_id UUID        NOT NULL REFERENCES ticket_types (id),
    quantity       INTEGER     NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    expires_at     TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_reservations_quantity CHECK (quantity > 0),
    CONSTRAINT ck_reservations_status CHECK (status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'CANCELLED'))
);

-- Partial index used by the expiration job: only ACTIVE rows are scanned by expires_at.
CREATE INDEX ix_reservations_active_expires_at ON reservations (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX ix_reservations_user_id ON reservations (user_id);
