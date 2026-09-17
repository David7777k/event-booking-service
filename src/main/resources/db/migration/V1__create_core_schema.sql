-- Required by the exclusion constraint on event: GiST cannot index a plain
-- equality on bigint without this extension.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- "user" is reserved in SQL, hence the prefix.
CREATE TABLE app_user (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT app_user_email_key  UNIQUE (email),
    CONSTRAINT app_user_role_check CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE venue (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT        NOT NULL,
    address    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A physical seat. It exists independently of any event held at the venue.
-- No separate index on venue_id: the unique constraint below already provides
-- a btree with venue_id as its leading column.
CREATE TABLE seat (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id    BIGINT NOT NULL REFERENCES venue (id) ON DELETE CASCADE,
    section     TEXT   NOT NULL,
    row_label   TEXT   NOT NULL,
    seat_number INT    NOT NULL,
    CONSTRAINT seat_unique_in_venue   UNIQUE (venue_id, section, row_label, seat_number),
    CONSTRAINT seat_number_positive   CHECK (seat_number > 0)
);

CREATE TABLE event (
    id             BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id       BIGINT      NOT NULL REFERENCES venue (id),
    title          TEXT        NOT NULL,
    description    TEXT        NOT NULL DEFAULT '',
    starts_at      TIMESTAMPTZ NOT NULL,
    ends_at        TIMESTAMPTZ NOT NULL,
    sales_start_at TIMESTAMPTZ NOT NULL,
    sales_end_at   TIMESTAMPTZ NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'DRAFT',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Derived, so it can never drift out of sync with the columns it covers.
    time_range TSTZRANGE GENERATED ALWAYS AS
        (tstzrange(starts_at, ends_at, '[)')) STORED,

    -- The text search configuration is named explicitly: to_tsvector/1 depends
    -- on default_text_search_config and is therefore not IMMUTABLE, which a
    -- generated column requires.
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')),       'A') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'B')
    ) STORED,

    CONSTRAINT event_status_check       CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED')),
    CONSTRAINT event_time_order         CHECK (ends_at > starts_at),
    CONSTRAINT event_sales_order        CHECK (sales_end_at > sales_start_at),
    CONSTRAINT event_sales_before_start CHECK (sales_start_at < starts_at)
);

-- A venue cannot host two events at overlapping times. Enforced by the
-- database rather than by a read-then-write check in application code, which
-- would be vulnerable to exactly the race this project is about.
-- Cancelled events are excluded so they stop blocking the slot.
ALTER TABLE event ADD CONSTRAINT event_no_overlap_per_venue
    EXCLUDE USING gist (venue_id WITH =, time_range WITH &&)
    WHERE (status <> 'CANCELLED');

CREATE INDEX event_search_idx ON event USING gin (search_vector);

-- Partial: the catalogue only ever lists published events, so unpublished
-- rows are dead weight in this index.
CREATE INDEX event_published_starts_idx ON event (starts_at) WHERE status = 'PUBLISHED';

CREATE TABLE booking (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        BIGINT        NOT NULL REFERENCES event (id),
    user_id         BIGINT        NOT NULL REFERENCES app_user (id),
    status          TEXT          NOT NULL DEFAULT 'PENDING',
    total_amount    NUMERIC(12,2) NOT NULL,
    expires_at      TIMESTAMPTZ,
    confirmed_at    TIMESTAMPTZ,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT booking_status_check        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT booking_total_non_negative  CHECK (total_amount >= 0),
    CONSTRAINT booking_pending_has_expiry  CHECK (status <> 'PENDING'   OR expires_at IS NOT NULL),
    CONSTRAINT booking_confirmed_has_time  CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
);

-- Idempotent confirmation, scoped per user so keys cannot collide across users.
CREATE UNIQUE INDEX booking_idempotency_key_idx
    ON booking (user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX booking_user_created_idx ON booking (user_id, created_at DESC);

-- Drives the expiry worker, which only ever scans pending bookings.
CREATE INDEX booking_expiry_idx ON booking (expires_at) WHERE status = 'PENDING';

-- One seat of one event is exactly one row. That is what makes double-selling
-- impossible to express in the schema at all: the row cannot hold two states.
CREATE TABLE event_seat (
    id         BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id   BIGINT        NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    seat_id    BIGINT        NOT NULL REFERENCES seat (id),
    price      NUMERIC(12,2) NOT NULL,
    status     TEXT          NOT NULL DEFAULT 'AVAILABLE',
    booking_id BIGINT        REFERENCES booking (id),
    version    BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT event_seat_unique            UNIQUE (event_id, seat_id),
    CONSTRAINT event_seat_status_check      CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    CONSTRAINT event_seat_price_non_negative CHECK (price >= 0),

    -- A taken seat must name the booking that took it, and a free seat must not.
    CONSTRAINT event_seat_booking_consistency CHECK (
        (status =  'AVAILABLE' AND booking_id IS NULL) OR
        (status <> 'AVAILABLE' AND booking_id IS NOT NULL)
    )
);

-- Partial: rendering a seat map asks for free seats of one event.
CREATE INDEX event_seat_available_idx ON event_seat (event_id) WHERE status = 'AVAILABLE';
CREATE INDEX event_seat_booking_idx   ON event_seat (booking_id) WHERE booking_id IS NOT NULL;
