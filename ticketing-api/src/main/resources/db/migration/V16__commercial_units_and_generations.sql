-- ADR-017: stable commercial units and issuance generations.

ALTER TABLE order_items
    ADD CONSTRAINT uq_order_items_id_order UNIQUE (id, order_id);

CREATE TABLE commercial_generations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id          UUID        NOT NULL REFERENCES orders (id),
    generation_number INTEGER     NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_commercial_generations_number CHECK (generation_number > 0),
    CONSTRAINT ck_commercial_generations_status
        CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT uq_commercial_generations_order_number UNIQUE (order_id, generation_number),
    CONSTRAINT uq_commercial_generations_id_order UNIQUE (id, order_id)
);

CREATE UNIQUE INDEX ux_commercial_generations_one_active
    ON commercial_generations (order_id) WHERE status = 'ACTIVE';

CREATE TABLE commercial_units (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID        NOT NULL,
    order_item_id UUID        NOT NULL,
    unit_number   INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_commercial_units_number CHECK (unit_number > 0),
    CONSTRAINT fk_commercial_units_item_order
        FOREIGN KEY (order_item_id, order_id) REFERENCES order_items (id, order_id),
    CONSTRAINT uq_commercial_units_item_number UNIQUE (order_item_id, unit_number),
    CONSTRAINT uq_commercial_units_id_order UNIQUE (id, order_id),
    CONSTRAINT uq_commercial_units_id_item_order UNIQUE (id, order_item_id, order_id)
);

CREATE INDEX ix_commercial_units_order_id ON commercial_units (order_id);

CREATE TABLE commercial_unit_generations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id           UUID        NOT NULL,
    commercial_unit_id UUID        NOT NULL,
    generation_id      UUID        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_unit_generations_unit_order
        FOREIGN KEY (commercial_unit_id, order_id) REFERENCES commercial_units (id, order_id),
    CONSTRAINT fk_unit_generations_generation_order
        FOREIGN KEY (generation_id, order_id) REFERENCES commercial_generations (id, order_id),
    CONSTRAINT uq_unit_generations_unit_generation UNIQUE (commercial_unit_id, generation_id)
);

CREATE INDEX ix_unit_generations_generation_id ON commercial_unit_generations (generation_id);

ALTER TABLE tickets
    ADD COLUMN order_id           UUID,
    ADD COLUMN commercial_unit_id UUID,
    ADD COLUMN generation_id      UUID;

-- Backfill: generation 1 for every order that already has tickets.
INSERT INTO commercial_generations (order_id, generation_number, status)
SELECT oi.order_id,
       1,
       CASE
           WHEN bool_or(t.status IN ('VALID', 'USED')) THEN 'ACTIVE'
           ELSE 'CANCELLED'
       END
FROM tickets t
JOIN order_items oi ON oi.id = t.order_item_id
GROUP BY oi.order_id;

-- One unit per purchased quantity; GREATEST keeps every legacy ticket mappable.
INSERT INTO commercial_units (order_id, order_item_id, unit_number)
SELECT oi.order_id, oi.id, series.unit_number
FROM order_items oi
JOIN commercial_generations g ON g.order_id = oi.order_id
CROSS JOIN LATERAL generate_series(
    1,
    GREATEST(oi.quantity, (SELECT COUNT(*) FROM tickets t WHERE t.order_item_id = oi.id))::INTEGER
) AS series (unit_number);

INSERT INTO commercial_unit_generations (order_id, commercial_unit_id, generation_id)
SELECT u.order_id, u.id, g.id
FROM commercial_units u
JOIN commercial_generations g ON g.order_id = u.order_id AND g.generation_number = 1;

-- Deterministic mapping: n-th legacy ticket of an item (by created_at, id) -> unit n.
WITH ranked AS (
    SELECT t.id,
           t.order_item_id,
           ROW_NUMBER() OVER (PARTITION BY t.order_item_id ORDER BY t.created_at, t.id) AS unit_number
    FROM tickets t
)
UPDATE tickets t
SET order_id           = u.order_id,
    commercial_unit_id = u.id,
    generation_id      = g.id
FROM ranked r
JOIN commercial_units u
    ON u.order_item_id = r.order_item_id AND u.unit_number = r.unit_number
JOIN commercial_generations g
    ON g.order_id = u.order_id AND g.generation_number = 1
WHERE t.id = r.id;

ALTER TABLE tickets
    ALTER COLUMN order_id SET NOT NULL,
    ALTER COLUMN commercial_unit_id SET NOT NULL,
    ALTER COLUMN generation_id SET NOT NULL,
    ADD CONSTRAINT fk_tickets_unit_item_order
        FOREIGN KEY (commercial_unit_id, order_item_id, order_id)
            REFERENCES commercial_units (id, order_item_id, order_id),
    ADD CONSTRAINT fk_tickets_unit_generation
        FOREIGN KEY (commercial_unit_id, generation_id)
            REFERENCES commercial_unit_generations (commercial_unit_id, generation_id),
    ADD CONSTRAINT fk_tickets_generation_order
        FOREIGN KEY (generation_id, order_id) REFERENCES commercial_generations (id, order_id),
    ADD CONSTRAINT uq_tickets_unit_generation UNIQUE (commercial_unit_id, generation_id);

CREATE INDEX ix_tickets_order_id ON tickets (order_id);
CREATE INDEX ix_tickets_generation_id ON tickets (generation_id);

-- Units and unit/generation links are identities: never rewritten or deleted.
CREATE TRIGGER trg_commercial_units_append_only
    BEFORE UPDATE OR DELETE ON commercial_units
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER trg_commercial_unit_generations_append_only
    BEFORE UPDATE OR DELETE ON commercial_unit_generations
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

-- ACTIVE -> SUPERSEDED | CANCELLED; SUPERSEDED and CANCELLED are final.
CREATE FUNCTION enforce_commercial_generation_transition()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'commercial_generations rows cannot be deleted';
    END IF;
    IF NEW.order_id <> OLD.order_id OR NEW.generation_number <> OLD.generation_number THEN
        RAISE EXCEPTION 'commercial generation identity is immutable';
    END IF;
    IF NEW.status <> OLD.status AND OLD.status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'commercial generation in status % is final', OLD.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_commercial_generations_transition
    BEFORE UPDATE OR DELETE ON commercial_generations
    FOR EACH ROW EXECUTE FUNCTION enforce_commercial_generation_transition();

-- VALID -> USED | CANCELLED; USED and CANCELLED are final; issuance identity is immutable.
CREATE FUNCTION enforce_ticket_transition()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF NEW.order_id <> OLD.order_id
        OR NEW.order_item_id <> OLD.order_item_id
        OR NEW.commercial_unit_id <> OLD.commercial_unit_id
        OR NEW.generation_id <> OLD.generation_id
        OR NEW.code <> OLD.code THEN
        RAISE EXCEPTION 'ticket issuance identity is immutable';
    END IF;
    IF NEW.status <> OLD.status AND OLD.status <> 'VALID' THEN
        RAISE EXCEPTION 'ticket in status % is final', OLD.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_tickets_transition
    BEFORE UPDATE ON tickets
    FOR EACH ROW EXECUTE FUNCTION enforce_ticket_transition();
