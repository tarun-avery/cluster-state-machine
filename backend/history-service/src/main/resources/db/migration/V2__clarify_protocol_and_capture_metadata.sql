-- V1 remains unchanged. Separate schema IDs from actual wire versions.
ALTER TABLE incoming RENAME COLUMN protocol_version TO protocol_schema_id;
ALTER TABLE outgoing RENAME COLUMN protocol_version TO protocol_schema_id;
ALTER TABLE incoming ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outgoing ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE incoming ADD COLUMN capture_kind TEXT NOT NULL DEFAULT 'UNCLASSIFIED';
ALTER TABLE incoming ADD CONSTRAINT incoming_capture_kind CHECK (capture_kind IN ('UNCLASSIFIED','CAPTURED','RECONSTRUCTED'));
