CREATE TABLE operation_cycles (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id   UUID        NOT NULL,
    cycle_number   INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_operation_cycles_number CHECK (cycle_number > 0),
    CONSTRAINT uq_operation_cycle UNIQUE (aggregate_type, aggregate_id, cycle_number)
);

CREATE TABLE operation_participations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id          UUID NOT NULL REFERENCES operation_cycles (id),
    provider_event_id UUID NOT NULL REFERENCES provider_events (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_operation_participation UNIQUE (cycle_id, provider_event_id)
);

CREATE INDEX ix_operation_participations_event ON operation_participations (provider_event_id);

CREATE TABLE operation_jobs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    participation_id UUID NOT NULL REFERENCES operation_participations (id),
    generation       INTEGER     NOT NULL,
    effective        BOOLEAN     NOT NULL DEFAULT TRUE,
    fencing_token    BIGINT      NOT NULL DEFAULT 0,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count    INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ DEFAULT clock_timestamp(),
    last_error       VARCHAR(120),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_operation_jobs_generation CHECK (generation > 0),
    CONSTRAINT ck_operation_jobs_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_operation_jobs_status CHECK (status IN (
        'PENDING', 'CLAIMED', 'APPLIED', 'PROJECTED', 'COMPLETED', 'FAILED', 'REPLACED'
    )),
    CONSTRAINT uq_operation_job_generation UNIQUE (participation_id, generation)
);

CREATE UNIQUE INDEX ux_operation_jobs_effective
    ON operation_jobs (participation_id) WHERE effective;
CREATE INDEX ix_operation_jobs_recovery
    ON operation_jobs (status, next_attempt_at) WHERE effective;

CREATE TABLE operation_executions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           UUID NOT NULL REFERENCES operation_jobs (id),
    worker_id        VARCHAR(100) NOT NULL,
    fencing_token    BIGINT       NOT NULL,
    lease_expires_at TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'CLAIMED',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_operation_executions_status CHECK (status IN (
        'CLAIMED', 'APPLIED', 'PROJECTED', 'COMPLETED', 'FAILED', 'INVALIDATED'
    )),
    CONSTRAINT uq_operation_execution_token UNIQUE (job_id, fencing_token)
);

CREATE INDEX ix_operation_executions_lease ON operation_executions (job_id, lease_expires_at);

CREATE TABLE operation_dispositions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id     UUID NOT NULL REFERENCES operation_executions (id),
    disposition_type VARCHAR(20) NOT NULL,
    payload          JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_operation_dispositions_type
        CHECK (disposition_type IN ('APPLIED', 'NO_OP', 'FAILED')),
    CONSTRAINT uq_operation_disposition_execution UNIQUE (execution_id)
);

CREATE TRIGGER trg_operation_dispositions_append_only
    BEFORE UPDATE OR DELETE ON operation_dispositions
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TABLE operation_projections (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    participation_id  UUID NOT NULL REFERENCES operation_participations (id),
    technical_status  VARCHAR(20) NOT NULL,
    commercial_status VARCHAR(30) NOT NULL,
    financial_status  VARCHAR(30) NOT NULL,
    fencing_token     BIGINT NOT NULL,
    version           BIGINT NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_operation_projections_version CHECK (version > 0),
    CONSTRAINT uq_operation_projection_participation UNIQUE (participation_id)
);
