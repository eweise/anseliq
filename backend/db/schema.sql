drop database if exists recon_db;

create database recon_db;

\c recon_db;

create schema if not exists recon;

-- Named app_user, not user: "user" is a reserved word in Postgres (collides with the CURRENT_USER
-- function), which would force every raw-SQL reference to be quoted. role is a fixed, global enum
-- (UserRole: Admin/Analyst/Viewer) persisted as plain text, matching this schema's existing
-- convention for every other Scala enum column (job.job_status, dispute.status, job.job_type) —
-- no Postgres CREATE TYPE, no custom codec. Stored lowercase ('admin', not 'Admin'): the @Table(...,
-- SqlNameMapper.CamelToSnakeCase) on UserRole downcases enum case names too, not just column/table
-- names, so the literal text a DbCodec writes/expects is lowercase, confirmed by direct testing
-- (this is the exact same lowercase-vs-capitalized mismatch already present, and noted as harmless
-- since always overridden by application code, on dispute.status's 'new' default).
create table recon.app_user
(
    id            uuid primary key,
    email         text                                  not null unique,
    password_hash text                                  not null,
    first_name    text                                  not null,
    last_name     text                                  not null,
    role          text        default 'viewer'          not null,
    active        bool        default true              not null,
    created_at    timestamptz default CURRENT_TIMESTAMP not null,
    updated_at    timestamptz default CURRENT_TIMESTAMP not null
);

-- Seed one default Admin so the app is usable right after `make create-db`. Password is
-- "changeme123" (bcrypt, cost 12) — rotate it immediately via the Users & Roles admin page.
insert into recon.app_user (id, email, password_hash, first_name, last_name, role)
values ('00000000-0000-0000-0000-000000000001', 'admin@onparity.local',
        '$2y$12$/w6Sva4dYXUtPOrWuveq3O/lTdh6uVXa3IYDpklb4NK.6JGkPOiPm', 'Admin', 'User', 'admin');

create table recon.fund_flow_config
(
    id                 uuid primary key,
    name               text                                  not null unique,
    created_at         timestamptz default CURRENT_TIMESTAMP not null,
    updated_at         timestamptz default CURRENT_TIMESTAMP not null,
    -- Hour of day (0-23, server local time) after which the daily reporting snapshot for "today"
    -- is allowed to be generated. Defaults to 0 (midnight) so it fires as soon as the day rolls
    -- over unless a fund flow configures a later cutoff to let its data settle first.
    daily_cutoff_hours int         default 0                 not null
);

create table recon.account
(
    id                   uuid primary key,
    fund_flow_id         uuid           not null references recon.fund_flow_config (id) ON DELETE CASCADE,
    name                 text           not null,
    balance              numeric(15, 2) not null default 0,
    reconciled_balance   numeric(15, 2) not null default 0,
    unreconciled_balance numeric(15, 2) not null default 0,
    account_type         text           not null,
    properties           jsonb,
    created_at           timestamptz             default CURRENT_TIMESTAMP not null,
    updated_at           timestamptz             default CURRENT_TIMESTAMP not null
);

CREATE INDEX account_props_index
    ON recon.account
        USING GIN (properties jsonb_path_ops);

-- Owns "where a file comes from and how to parse it" — the part of ingestion that's shared across
-- every account a file touches. One row per physical file source (e.g. one BAI2 drop location);
-- multiple movement_spec rows can route off the same feed, one per account the file covers.
create table recon.file_feed
(
    id                     uuid primary key,
    name                   text,
    data_source_json       text                                  not null,
    expected_cadence_hours int,
    max_parse_errors       int,
    created_at             timestamptz default CURRENT_TIMESTAMP not null,
    updated_at             timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.movement_spec
(
    id                  uuid primary key,
    name                text,
    in_account_id       uuid                                  not null REFERENCES recon.account (id) ON DELETE CASCADE,
    out_account_id      uuid                                  not null REFERENCES recon.account (id) ON DELETE CASCADE,
    file_feed_id        uuid                                  not null REFERENCES recon.file_feed (id) ON DELETE CASCADE,
    -- Encoded RoutingFilter (propertyField + matchValue) JSON, NULL means this spec claims every
    -- row from its feed — the default for a feed with only one account (today's only case, e.g.
    -- CSV). A non-null filter is how a multi-account feed (e.g. BAI2) splits rows across accounts
    -- by a parsed field like account_number.
    routing_filter_json text,
    created_at          timestamptz default CURRENT_TIMESTAMP not null,
    updated_at          timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.reconciliation_spec
(
    id                   uuid primary key,
    account_id           uuid                                  not null REFERENCES recon.account (id),
    aging_limit          int,
    relationship         text                                  not null,
    in_movement_spec_id  uuid                                  not null REFERENCES recon.movement_spec (id),
    out_movement_spec_id uuid                                  not null REFERENCES recon.movement_spec (id),
    rule_json            text, -- todo. should be jsonB?
    created_at           timestamptz default CURRENT_TIMESTAMP not null,
    updated_at           timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.reconciliation
(
    id                uuid primary key,
    -- Nullable: null only for a system-generated Reversed reconciliation (see
    -- ReconciliationStatus.Reversed), which links a movement to its Ledger.reverseMovement
    -- contra-entry rather than governing a real matching rule.
    spec_id           uuid REFERENCES recon.reconciliation_spec (id),
    description       text,
    applied_rules     jsonb                                 not null,
    balance           numeric(15, 2)                        not null default 0,
    status            text                                  not null,
    adjustment_reason text,
    created_at        timestamptz default CURRENT_TIMESTAMP not null,
    updated_at        timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.dispute
(
    id              uuid primary key,
    reference_id    uuid                                  not null,
    target_type     text                                  not null,
    category        text                                  not null,
    detail          text,
    status          text                                  not null default 'new',
    resolution_note text,
    created_at      timestamptz default CURRENT_TIMESTAMP not null,
    updated_at      timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX dispute_reference_id ON recon.dispute (reference_id);
CREATE INDEX dispute_status_created_at ON recon.dispute (status, created_at);

ALTER TABLE recon.dispute
    DROP COLUMN resolution_note;

create table recon.dispute_comment
(
    id                      uuid primary key,
    dispute_id              uuid                                  not null REFERENCES recon.dispute (id) ON DELETE CASCADE,
    body                    text,
    new_status              text,
    new_assigned_to_user_id uuid REFERENCES recon.app_user (id),
    -- Another dispute this entry links to (see DisputeComment.relatedDisputeId).
    related_dispute_id      uuid REFERENCES recon.dispute (id) ON DELETE SET NULL,
    created_at              timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.movement
(
    id                  uuid primary key,
    reference_id        uuid                                  not null UNIQUE,
    in_account_id       uuid                                  not null REFERENCES recon.account (id) ON DELETE CASCADE,
    in_account_balance  numeric(15, 2)                        not null,
    out_account_id      uuid                                  not null REFERENCES recon.account (id) ON DELETE CASCADE,
    out_account_balance numeric(15, 2)                        not null,
    movement_spec_id    uuid                                  not null REFERENCES recon.movement_spec (id),
    properties          jsonb,
    meta_data           jsonb,
    amount              numeric(15, 2)                        not null,
    reconciliation_id   uuid REFERENCES recon.reconciliation (id) ON DELETE CASCADE,
    -- Nullable, no backfill for existing rows (no migration tooling yet — see
    -- feedback_no_backfill_until_migration_strategy). Every new write sets it; a null here just
    -- means "predates this column", not "unknown type" in any meaningful sense.
    movement_type       text,
    -- Set only when movement_type is 'manual_adjustment': why it was posted (see AdjustmentType)
    -- and the note given with it.
    adjustment_type     text,
    adjustment_note     text,
    created_at          timestamptz default CURRENT_TIMESTAMP not null,
    updated_at          timestamptz default CURRENT_TIMESTAMP not null
);

CREATE TABLE recon.file_inventory
(
    id                     UUID PRIMARY KEY,
    file_feed_id           UUID                     NOT NULL,
    source_system          VARCHAR(50)              NOT NULL,
    file_source_entry_json TEXT                     NOT NULL,
    file_size_bytes        BIGINT                   NOT NULL,
    last_modified_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    status                 TEXT                     NOT NULL DEFAULT 'QUEUED',
    last_processed_at      TIMESTAMP WITH TIME ZONE,
    last_processed_line    BIGINT,
    created_at             TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_source_path UNIQUE (source_system, file_source_entry_json)
);

CREATE INDEX idx_inventory_lookup ON recon.file_inventory (source_system, file_source_entry_json);

CREATE INDEX movement_movement_spec_fk ON recon.movement (movement_spec_id);
CREATE INDEX movement_in_account_fk ON recon.movement (in_account_id);
CREATE INDEX movement_out_account_fk ON recon.movement (out_account_id);

-- Matches the WHERE clause of findUnreconciledPage/findUnreconciledByExactMatchKey exactly, so
-- reconciliation's keyset-paginated scan of a spec's unreconciled movements doesn't have to walk
-- every already-reconciled row for that spec first. Shrinks automatically as movements reconcile.
CREATE INDEX movement_unreconciled_by_spec ON recon.movement (movement_spec_id) WHERE reconciliation_id IS NULL;

-- fastupdate=false: ingestion already batches thousands of rows per transaction (see
-- Ledger.recordMovements), so fastupdate's pending-list buffering doesn't help throughput here —
-- it just defers cost into large, unpredictable synchronous merge stalls as the index grows.
-- Measured on a 1M-row synthetic ingest: fastupdate on took 67.7s total (worst batch 1.6s from a
-- merge stall); off took 18.4s total (worst batch 0.4s, smooth growth).
CREATE INDEX movement_props_index
    ON recon.movement
        USING GIN (properties jsonb_path_ops)
    WITH (fastupdate = false);

-- Backs MovementRepoLive.findByFileSourceEntry (bulk-unreconcile-by-file): without this, an exact
-- lookup on meta_data->'props'->>'file_source_entry' is a full table scan, since meta_data carries
-- no index of its own. Same fastupdate=false reasoning as movement_props_index above.
CREATE INDEX movement_meta_data_index
    ON recon.movement
        USING GIN (meta_data jsonb_path_ops)
    WITH (fastupdate = false);


create table recon.job
(
    id            uuid primary key,
    job_type      text                                  not null,
    job_status    text                                  not null,
    job_info_json text,
    progress_json text,
    reference_id  uuid                                  not null,
    error_message text,
    -- While in_progress: the worker's lease, extended by its heartbeat. Once it lapses the worker
    -- is presumed dead and the job is requeued (see JobManager.releaseExpiredLeases).
    locked_until  timestamptz,
    -- How many times a worker has claimed this job; failed and abandoned runs are retried, with
    -- backoff, until JobManager's maxAttempts.
    attempts      int         default 0                 not null,
    -- Not claimable before this time (a retry's backoff); null means as soon as possible.
    run_at        timestamptz,
    created_at    timestamptz default CURRENT_TIMESTAMP not null,
    updated_at    timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX job_reference_id_fk ON recon.job (reference_id, created_at);

-- At most one queued job per (job_type, reference_id): repeat submissions while one is waiting
-- (event redelivery, a periodic submitter racing a slow poller, a manual retrigger) coalesce into
-- it. A running job doesn't count, so work that arrives mid-run still gets a follow-up run.
CREATE UNIQUE INDEX job_submitted_unique ON recon.job (job_type, reference_id)
    WHERE job_status = 'submitted';

-- At most one running job per (job_type, reference_id), e.g. never two reconciliations of the
-- same account at once, whichever instance claims it.
CREATE UNIQUE INDEX job_in_progress_unique ON recon.job (job_type, reference_id)
    WHERE job_status = 'in_progress';

CREATE INDEX job_job_status_create_at_fk ON recon.job (job_status, created_at);

-- An append-only record of what happened, for the activity feed. Not a work queue: background work
-- is coordinated through recon.job.
create table recon.event_state
(
    id            uuid primary key,
    event_type    text not null,
    payload       text not null,
    created_at    timestamptz   default CURRENT_TIMESTAMP not null
);

CREATE INDEX event_type_created_at ON recon.event_state (event_type, created_at);

create table recon.daily_snapshot
(
    id                           uuid primary key,
    fund_flow_id                 uuid not null REFERENCES recon.fund_flow_config (id) ON DELETE CASCADE,
    snapshot_date                date not null,
    match_rates_json             text not null default '[]',
    -- reconciledBalance/balance*100 — a $ coverage ratio, not a rate; can legitimately exceed 100%
    -- since balance is a running net position while reconciledBalance only ever grows.
    avg_balance_coverage_percent numeric(7, 2),
    rated_account_count          int  not null default 0,
    exceptions_by_category_json  text not null default '{}',
    exceptions_by_status_json    text not null default '{}',
    open_exception_count         int  not null default 0,
    aging_zero_to_three          int  not null default 0,
    aging_four_to_seven          int  not null default 0,
    aging_eight_to_fourteen      int  not null default 0,
    aging_fifteen_plus           int  not null default 0,
    overdue_count                int  not null default 0,
    created_at                   timestamptz   default CURRENT_TIMESTAMP not null,
    updated_at                   timestamptz   default CURRENT_TIMESTAMP not null,
    UNIQUE (fund_flow_id, snapshot_date)
);

CREATE INDEX daily_snapshot_fund_flow_date_idx ON recon.daily_snapshot (fund_flow_id, snapshot_date);

ALTER TABLE recon.daily_snapshot
    -- The real match rate: reconciledMovementCount/totalMovementCount*100, a true 0-100% ratio —
    -- unlike avg_balance_coverage_percent above, which despite its old name ("match rate") was
    -- never actually a rate of anything.
    ADD COLUMN avg_match_rate_percent    numeric(5, 2),
    ADD COLUMN reconciled_movement_count int not null default 0,
    ADD COLUMN total_movement_count      int not null default 0;

-- At most one open (non-resolved) dispute per (reference_id, category) pair, so auto-raising a
-- dispute on every reconciliation run is idempotent via ON CONFLICT ... DO NOTHING rather than a
-- check-then-insert race. Scoped to category (not just reference_id) so an unrelated dispute type
-- can still be raised against the same reconciliation/movement; excludes resolved disputes so a
-- genuinely new occurrence of the same issue after a prior one was resolved isn't blocked forever.
CREATE UNIQUE INDEX dispute_reference_id_category_open_unique
    ON recon.dispute (reference_id, category)
    WHERE status <> 'resolved';


-- Added here (not inline in recon.dispute's own create table above) because recon.app_user must
-- already exist for these FK references to resolve on a fresh `make create-db` run — app_user is
-- defined later in this file than dispute. "on delete set null", not cascade: deleting a user
-- shouldn't delete the disputes they raised/were assigned, just orphan the reference.
ALTER TABLE recon.dispute
    ADD COLUMN raised_by_user_id   uuid REFERENCES recon.app_user (id) ON DELETE SET NULL,
    ADD COLUMN assigned_to_user_id uuid REFERENCES recon.app_user (id) ON DELETE SET NULL;

-- A secondary matcher's proposal (see MatchSuggester) for an unreconciled movement the recon spec's
-- rule couldn't match. Never a reconciliation itself: a person accepts or rejects it, and accepting
-- goes through ManualReconciler like any manual match. suggested_movement_ids_json is the movement
-- ids proposed to reconcile with movement_id, a JSON array.
create table recon.match_suggestion
(
    id                          uuid primary key,
    recon_spec_id               uuid           not null REFERENCES recon.reconciliation_spec (id) ON DELETE CASCADE,
    movement_id                 uuid           not null REFERENCES recon.movement (id) ON DELETE CASCADE,
    suggested_movement_ids_json text           not null,
    confidence                  numeric(4, 3)  not null,
    rationale                   text           not null,
    -- The suggester's explanation as a RuleMatch, JSON; only stored once checked against the movements.
    explanation_json            text,
    -- Which model and prompt produced it, so a suggestion can be traced after either changes.
    model                       text           not null,
    prompt_version              text           not null,
    status                      text           not null default 'pending',
    -- Who accepted or rejected it: null while pending, or once that user is deleted.
    reviewed_by_user_id         uuid           REFERENCES recon.app_user (id) ON DELETE SET NULL,
    created_at                  timestamptz default CURRENT_TIMESTAMP not null,
    updated_at                  timestamptz default CURRENT_TIMESTAMP not null
);

-- At most one pending suggestion per movement, so rerunning the matcher doesn't stack them.
CREATE UNIQUE INDEX match_suggestion_pending_unique ON recon.match_suggestion (movement_id)
    WHERE status = 'pending';
CREATE INDEX match_suggestion_recon_spec_status ON recon.match_suggestion (recon_spec_id, status);
