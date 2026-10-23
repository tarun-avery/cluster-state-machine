CREATE TABLE incoming (
    source_id UUID NOT NULL,
    command_position BIGINT NOT NULL,
    ingested_order BIGSERIAL UNIQUE NOT NULL,
    cluster_timestamp BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    protocol_version INTEGER NOT NULL,
    operation TEXT,
    quantity BIGINT,
    raw_request BYTEA NOT NULL,
    PRIMARY KEY (source_id, command_position)
);
CREATE TABLE outgoing (
    source_id UUID NOT NULL,
    command_position BIGINT NOT NULL,
    status TEXT NOT NULL,
    count BIGINT NOT NULL,
    protocol_version INTEGER NOT NULL,
    raw_result BYTEA NOT NULL,
    public_recording_id BIGINT NOT NULL,
    public_position BIGINT NOT NULL,
    PRIMARY KEY (source_id, command_position),
    FOREIGN KEY (source_id, command_position) REFERENCES incoming (source_id, command_position)
);
CREATE INDEX outgoing_replay_position_idx ON outgoing (public_recording_id, public_position);
