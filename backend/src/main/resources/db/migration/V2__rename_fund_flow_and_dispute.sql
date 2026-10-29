-- Names the tables after what the app calls them: a fund flow is a ledger, and a dispute an
-- exception (exception_case, since "exception" alone reads as an error everywhere in code).

ALTER TABLE recon.fund_flow_config RENAME TO ledger;
ALTER TABLE recon.account RENAME COLUMN fund_flow_id TO ledger_id;
ALTER TABLE recon.daily_snapshot RENAME COLUMN fund_flow_id TO ledger_id;

ALTER TABLE recon.dispute RENAME TO exception_case;
ALTER TABLE recon.dispute_comment RENAME TO exception_comment;
ALTER TABLE recon.exception_comment RENAME COLUMN dispute_id TO exception_id;
ALTER TABLE recon.exception_comment RENAME COLUMN related_dispute_id TO related_exception_id;

-- Constraint and index names still carry the old ones. Renaming a primary key or unique
-- constraint renames its index too, so the indexes left over are renamed after.
CREATE FUNCTION pg_temp.renamed(name text) RETURNS text LANGUAGE sql AS
$$
SELECT replace(replace(replace(regexp_replace(regexp_replace(name,
    '^dispute_comment', 'exception_comment'), '^dispute', 'exception_case'),
    'dispute', 'exception'), 'fund_flow_config', 'ledger'), 'fund_flow', 'ledger')
$$;

DO
$$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT conrelid::regclass AS tbl, conname
             FROM pg_constraint
             WHERE connamespace = 'recon'::regnamespace AND conname ~ 'dispute|fund_flow'
    LOOP
        EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I', r.tbl, r.conname, pg_temp.renamed(r.conname));
    END LOOP;
    FOR r IN SELECT indexname FROM pg_indexes WHERE schemaname = 'recon' AND indexname ~ 'dispute|fund_flow'
    LOOP
        EXECUTE format('ALTER INDEX recon.%I RENAME TO %I', r.indexname, pg_temp.renamed(r.indexname));
    END LOOP;
END
$$;

-- Events and job info are stored as JSON named after the Scala classes and fields.
UPDATE recon.event_state
SET event_type = replace(event_type, 'Dispute', 'Exception'),
    payload    = replace(replace(payload, '"$type":"Dispute', '"$type":"Exception'), '"disputeID":', '"exceptionID":')
WHERE event_type LIKE 'Dispute%';

UPDATE recon.job
SET job_info_json = replace(job_info_json, '"fundFlowsSnapshotted":', '"ledgersSnapshotted":')
WHERE job_info_json LIKE '%"fundFlowsSnapshotted":%';
