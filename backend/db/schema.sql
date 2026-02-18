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
    id           uuid primary key,
    fund_flow_id uuid                                  not null references recon.fund_flow_config (id) ON DELETE CASCADE,
    name         text                                  not null,
    balance      numeric(15, 2),
    properties   jsonb,
    created_at   timestamptz default CURRENT_TIMESTAMP not null,
    updated_at   timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX account_props_index
    ON recon.account
        USING GIN (properties jsonb_path_ops);

create table recon.reconciliation_spec
(
    id                  uuid primary key,
    fund_flow_id        uuid                                  not null REFERENCES recon.fund_flow_config (id),
    description         text,
    aging_limit         int,
    matching_rules_json text, -- todo. should be jsonB?
    created_at          timestamptz default CURRENT_TIMESTAMP not null,
    updated_at          timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.reconciliation
(
    id          uuid primary key,
    spec_id     uuid                                  not null REFERENCES recon.reconciliation_spec (id),
    description text,
    created_at  timestamptz default CURRENT_TIMESTAMP not null,
    updated_at  timestamptz default CURRENT_TIMESTAMP not null
);

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
create table recon.movement
(
    id                uuid primary key,
    reference_id      uuid                                  not null UNIQUE,
    in_account_id     uuid                                  not null REFERENCES recon.account (id) ON DELETE CASCADE,
    out_account_id    uuid                                  not null REFERENCES recon.account (id) ON DELETE CASCADE,
    movement_spec_id  uuid                                  not null REFERENCES recon.movement_spec (id),
    properties        jsonb,
    amount            numeric(15, 2)                        not null,
    status            text                                  not null,
    reconciliation_id uuid REFERENCES recon.reconciliation (id) ON DELETE CASCADE,
    created_at        timestamptz default CURRENT_TIMESTAMP not null,
    updated_at        timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX movement_movement_spec_fk ON recon.movement (movement_spec_id);
CREATE INDEX movement_in_account_fk ON recon.movement (in_account_id);
CREATE INDEX movement_out_account_fk ON recon.movement (out_account_id);

CREATE INDEX movement_props_index
    ON recon.movement
        USING GIN (properties jsonb_path_ops);


CREATE FUNCTION update_update_at_user_task()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_fund_flow_config_update_at
    BEFORE UPDATE
    ON
        recon.fund_flow_config
    FOR EACH ROW
EXECUTE PROCEDURE update_update_at_user_task();

CREATE TRIGGER update_account_update_at
    BEFORE UPDATE
    ON
        recon.account
    FOR EACH ROW
EXECUTE PROCEDURE update_update_at_user_task();

CREATE TRIGGER update_movement_spec_update_at
    BEFORE UPDATE
    ON
        recon.movement_spec
    FOR EACH ROW
EXECUTE PROCEDURE update_update_at_user_task();

CREATE TRIGGER update_movement_update_at
    BEFORE UPDATE
    ON
        recon.movement
    FOR EACH ROW
EXECUTE PROCEDURE update_update_at_user_task();

CREATE TRIGGER update_reconciliation_update_at
    BEFORE UPDATE
    ON
        recon.reconciliation
    FOR EACH ROW
EXECUTE PROCEDURE update_update_at_user_task();

CREATE TRIGGER update_reconciliation_spec_update_at
    BEFORE UPDATE
    ON
        recon.reconciliation_spec
    FOR EACH ROW
EXECUTE PROCEDURE update_update_at_user_task();
