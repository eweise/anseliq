drop database if exists recon_db;

create database recon_db;

\c recon_db;

create schema if not exists recon ;

create table recon.fund_flow_config(
    id uuid primary key,
    name text not null unique,
    fund_flow_json text not null,
    created_at timestamptz default CURRENT_TIMESTAMP not null,
    updated_at timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.account (
    id uuid primary key,
    fund_flow_id uuid not null references recon.fund_flow_config(id) ON DELETE CASCADE,
    name text not null,
    account_type text not null,
    balance numeric(15,2),
    tags text[],
    created_at timestamptz default CURRENT_TIMESTAMP not null,
    updated_at timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.reconciliation (
    id uuid primary key,
    description text,
    created_at timestamptz default CURRENT_TIMESTAMP not null,
    updated_at timestamptz default CURRENT_TIMESTAMP not null
);

create table recon.movement (
    id uuid primary key,
    reference_id uuid not null UNIQUE,
    in_account_id uuid not null REFERENCES recon.account (id) ON DELETE CASCADE,
    out_account_id uuid not null REFERENCES recon.account (id) ON DELETE CASCADE,
    properties jsonb,
    amount numeric(15, 2) not null,
    status text not null,
    reconciliation_id uuid REFERENCES recon.reconciliation (id) ON DELETE CASCADE,
    created_at timestamptz default CURRENT_TIMESTAMP not null,
    updated_at timestamptz default CURRENT_TIMESTAMP not null
);

CREATE INDEX movement_props_index
    ON recon.movement
    USING GIN (properties jsonb_path_ops);


CREATE  FUNCTION update_update_at_user_task()
RETURNS TRIGGER AS $$
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
