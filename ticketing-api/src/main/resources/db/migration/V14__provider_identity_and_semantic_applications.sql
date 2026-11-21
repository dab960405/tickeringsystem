CREATE TABLE provider_operations (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code         VARCHAR(40)  NOT NULL,
    merchant_account_id   VARCHAR(100) NOT NULL,
    identity_namespace    VARCHAR(60)  NOT NULL,
    provider_operation_id VARCHAR(100) NOT NULL,
    order_id              UUID         NOT NULL REFERENCES orders (id),
    operation_kind        VARCHAR(30)  NOT NULL,
    payload_digest        CHAR(64)     NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_provider_operations_kind
        CHECK (operation_kind IN ('PAYMENT_CAPTURE', 'PAYMENT_FAILURE', 'PAYMENT_REFUND')),
    CONSTRAINT uq_provider_operation_identity
        UNIQUE (provider_code, merchant_account_id, identity_namespace, provider_operation_id)
);

CREATE INDEX ix_provider_operations_order_id ON provider_operations (order_id);

CREATE TABLE provider_events (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code        VARCHAR(40)  NOT NULL,
    merchant_account_id  VARCHAR(100) NOT NULL,
    provider_event_id    UUID         NOT NULL,
    provider_operation_id UUID        NOT NULL REFERENCES provider_operations (id),
    payload              JSONB        NOT NULL,
    payload_digest       CHAR(64)     NOT NULL,
    received_at          TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_provider_event_identity
        UNIQUE (provider_code, merchant_account_id, provider_event_id)
);

CREATE INDEX ix_provider_events_operation_id ON provider_events (provider_operation_id);

CREATE TABLE semantic_applications (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_operation_id UUID         NOT NULL REFERENCES provider_operations (id),
    application_scope     VARCHAR(120) NOT NULL,
    operation_kind        VARCHAR(30)  NOT NULL,
    idempotency_key       VARCHAR(100) NOT NULL,
    payload_digest        CHAR(64)     NOT NULL,
    applied_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_semantic_applications_kind
        CHECK (operation_kind IN ('PAYMENT_CAPTURE', 'PAYMENT_FAILURE', 'PAYMENT_REFUND')),
    CONSTRAINT uq_semantic_application_operation UNIQUE (provider_operation_id),
    CONSTRAINT uq_semantic_application_identity
        UNIQUE (application_scope, operation_kind, idempotency_key)
);

CREATE FUNCTION reject_append_only_mutation()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_provider_operations_append_only
    BEFORE UPDATE OR DELETE ON provider_operations
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER trg_provider_events_append_only
    BEFORE UPDATE OR DELETE ON provider_events
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER trg_semantic_applications_append_only
    BEFORE UPDATE OR DELETE ON semantic_applications
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
