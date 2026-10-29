-- Event payloads are stored as JSON named after the Scala fields, whose ids are now spelled
-- accountId rather than accountID.
UPDATE recon.event_state
SET payload = regexp_replace(payload, '"([a-z][A-Za-z0-9]*[a-z0-9])ID":', '"\1Id":', 'g')
WHERE payload ~ '"[a-z][A-Za-z0-9]*[a-z0-9]ID":';
