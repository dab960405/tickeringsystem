CREATE TABLE events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organizer_id UUID         NOT NULL REFERENCES users (id),
    venue_id     UUID         NOT NULL REFERENCES venues (id),
    title        VARCHAR(200) NOT NULL,
    description  TEXT,
    category     VARCHAR(50)  NOT NULL,
    starts_at    TIMESTAMPTZ  NOT NULL,
    ends_at      TIMESTAMPTZ  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'FINISHED')),
    CONSTRAINT ck_events_dates CHECK (ends_at > starts_at)
);

CREATE INDEX ix_events_status_starts_at ON events (status, starts_at);
CREATE INDEX ix_events_venue_id ON events (venue_id);
CREATE INDEX ix_events_category ON events (category);
CREATE INDEX ix_events_organizer_id ON events (organizer_id);
