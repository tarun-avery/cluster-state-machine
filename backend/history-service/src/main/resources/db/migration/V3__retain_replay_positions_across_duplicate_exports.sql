-- A duplicate can occur in a new public recording after checkpoint loss.
-- Retain progress for every recording without adding a third business table.
ALTER TABLE outgoing ADD COLUMN replay_positions JSONB NOT NULL DEFAULT '{}';
UPDATE outgoing SET replay_positions = jsonb_build_object(public_recording_id::text, public_position);
