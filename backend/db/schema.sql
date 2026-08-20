drop database if exists recon_db;

create database recon_db;

\c recon_db;

create schema if not exists recon;

create table recon.fund_flow_config
(
    id         uuid primary key,
    name       text                                  not null unique,
    created_at timestamptz default CURRENT_TIMESTAMP not null,
    updated_at timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.account
(
    id                   uuid primary key,
    fund_flow_id         uuid           not null references recon.fund_flow_config (id) ON DELETE CASCADE,
    name                 text           not null,
    balance              numeric(15, 2) not null default 0,
    reconciled_balance   numeric(15, 2) not null default 0,
    unreconciled_balance numeric(15, 2) not null default 0,
    is_recon             bool           not null,
    properties           jsonb,
    created_at           timestamptz             default CURRENT_TIMESTAMP not null,
    updated_at           timestamptz             default CURRENT_TIMESTAMP not null
);

CREATE INDEX account_props_index
    ON recon.account
        USING GIN (properties jsonb_path_ops);

create table recon.movement_spec
(
    id               uuid primary key,
    name             text,
    in_account_id    uuid                                  not null REFERENCES recon.account (id) ON DELETE CASCADE,
    out_account_id   uuid                                  not null REFERENCES recon.account (id) ON DELETE CASCADE,
    data_source_json text                                  not null,
    created_at       timestamptz default CURRENT_TIMESTAMP not null,
    updated_at       timestamptz default CURRENT_TIMESTAMP not null
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
    spec_id           uuid                                  not null REFERENCES recon.reconciliation_spec (id),
    description       text,
    applied_rules     text,
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
    id         uuid primary key,
    dispute_id uuid                                  not null REFERENCES recon.dispute (id) ON DELETE CASCADE,
    body       text,
    new_status text,
    created_at timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX dispute_comment_dispute_id ON recon.dispute_comment (dispute_id, created_at);


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
    created_at          timestamptz default CURRENT_TIMESTAMP not null,
    updated_at          timestamptz default CURRENT_TIMESTAMP not null
);

CREATE TABLE recon.file_inventory
(
    id                     UUID PRIMARY KEY,
    movement_spec_id       UUID                     NOT NULL,
    source_system          VARCHAR(50)              NOT NULL,
    file_source_entry_json TEXT                     NOT NULL,
    file_size_bytes        BIGINT                   NOT NULL,
    last_modified_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    status                 TEXT                     NOT NULL DEFAULT 'QUEUED',
    last_processed_at      TIMESTAMP WITH TIME ZONE,
    last_processed_line    BIGINT,
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


create table recon.job
(
    id            uuid primary key,
    job_type      text                                  not null,
    job_status    text                                  not null,
    job_info_json text,
    progress_json text,
    reference_id  uuid                                  not null,
    error_message text,
    created_at    timestamptz default CURRENT_TIMESTAMP not null,
    updated_at    timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX job_reference_id_fk ON recon.job (reference_id, created_at);

-- At most one active (not yet Complete/Error) job per (job_type, reference_id): backstop against
-- duplicate submission (event redelivery, a periodic submitter racing a slow poller, a manual
-- retrigger, or a future multi-instance deployment) regardless of which caller wins the race.
CREATE UNIQUE INDEX job_active_unique ON recon.job (job_type, reference_id)
    WHERE job_status IN ('submitted', 'in_progress');

CREATE INDEX job_job_status_create_at_fk ON recon.job (job_status, created_at);

CREATE FUNCTION update_audit_fields_task()
    RETURNS TRIGGER AS
$$
BEGIN
    IF NEW.created_at IS NULL THEN
        NEW.created_at = now();
    END IF;
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_fund_flow_config
    BEFORE INSERT OR UPDATE
    ON
        recon.fund_flow_config
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();

CREATE TRIGGER update_account
    BEFORE INSERT OR UPDATE
    ON
        recon.account
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();

CREATE TRIGGER update_movement_spec
    BEFORE INSERT OR UPDATE
    ON
        recon.movement_spec
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();

CREATE TRIGGER update_movement
    BEFORE INSERT OR UPDATE
    ON
        recon.movement
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();

CREATE TRIGGER update_reconciliation
    BEFORE INSERT OR UPDATE
    ON
        recon.reconciliation
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();

CREATE TRIGGER update_reconciliation_spec
    BEFORE INSERT OR UPDATE
    ON
        recon.reconciliation_spec
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();

CREATE TRIGGER update_job
    BEFORE INSERT OR UPDATE
    ON
        recon.job
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();

create table recon.event_state
(
    id            uuid primary key,
    event_type    text not null,
    payload       text not null,
    status        text not null default 'Pending',
    retry_count   int  not null default 0,
    error_message text,
    created_at    timestamptz   default CURRENT_TIMESTAMP not null,
    updated_at    timestamptz   default CURRENT_TIMESTAMP not null
);

CREATE INDEX event_status_created_at ON recon.event_state (status, created_at);

CREATE TRIGGER update_event
    BEFORE INSERT OR UPDATE
    ON recon.event_state
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();

create table recon.daily_snapshot
(
    id                           uuid primary key,
    fund_flow_id                 uuid                                   not null REFERENCES recon.fund_flow_config (id) ON DELETE CASCADE,
    snapshot_date                date                                   not null,
    match_rates_json             text                                   not null default '[]',
    avg_match_rate_percent       numeric(5, 2),
    rated_account_count          int                                    not null default 0,
    exceptions_by_category_json  text                                   not null default '{}',
    exceptions_by_status_json    text                                   not null default '{}',
    open_exception_count         int                                    not null default 0,
    aging_zero_to_three          int                                    not null default 0,
    aging_four_to_seven          int                                    not null default 0,
    aging_eight_to_fourteen      int                                    not null default 0,
    aging_fifteen_plus           int                                    not null default 0,
    overdue_count                int                                    not null default 0,
    created_at                   timestamptz default CURRENT_TIMESTAMP  not null,
    updated_at                   timestamptz default CURRENT_TIMESTAMP  not null,
    UNIQUE (fund_flow_id, snapshot_date)
);

CREATE INDEX daily_snapshot_fund_flow_date_idx ON recon.daily_snapshot (fund_flow_id, snapshot_date);

CREATE TRIGGER update_daily_snapshot
    BEFORE INSERT OR UPDATE
    ON recon.daily_snapshot
    FOR EACH ROW
EXECUTE PROCEDURE update_audit_fields_task();
