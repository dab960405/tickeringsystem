ALTER TABLE reservations
    ADD COLUMN inventory_disposition VARCHAR(20);

UPDATE reservations
SET inventory_disposition = CASE
    WHEN status = 'ACTIVE' THEN 'HELD'
    WHEN status = 'CONFIRMED' THEN 'CONSUMED'
    WHEN status IN ('EXPIRED', 'CANCELLED') THEN 'RELEASED'
END;

ALTER TABLE reservations
    ALTER COLUMN inventory_disposition SET NOT NULL,
    ALTER COLUMN inventory_disposition SET DEFAULT 'HELD';

ALTER TABLE reservations
    ADD CONSTRAINT ck_reservations_inventory_disposition
        CHECK (inventory_disposition IN ('HELD', 'RELEASED', 'CONSUMED')),
    ADD CONSTRAINT ck_reservations_status_inventory_disposition
        CHECK (
            (status = 'ACTIVE' AND inventory_disposition = 'HELD')
            OR (status = 'CONFIRMED' AND inventory_disposition = 'CONSUMED')
            OR (status IN ('EXPIRED', 'CANCELLED') AND inventory_disposition = 'RELEASED')
        );
