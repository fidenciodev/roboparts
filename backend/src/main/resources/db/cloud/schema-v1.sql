-- Installed ONLY by the offline CloudDatabaseMaintenance command, never web startup.
-- Separate from core Flyway history so local installations retain their seven migrations.
CREATE TABLE roboparts.cloud_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    version INTEGER NOT NULL CHECK (version = 1)
);
INSERT INTO roboparts.cloud_state (id, version) VALUES (1, 1);

CREATE TABLE roboparts.web_sessions (
    primary_id CHAR(36) PRIMARY KEY,
    session_id CHAR(36) NOT NULL,
    creation_time BIGINT NOT NULL,
    last_access_time BIGINT NOT NULL,
    max_inactive_interval INTEGER NOT NULL,
    expiry_time BIGINT NOT NULL,
    principal_name VARCHAR(100)
);
CREATE UNIQUE INDEX web_sessions_id ON roboparts.web_sessions (session_id);
CREATE INDEX web_sessions_expiry ON roboparts.web_sessions (expiry_time);
CREATE INDEX web_sessions_principal ON roboparts.web_sessions (principal_name);
CREATE TABLE roboparts.web_sessions_attributes (
    session_primary_id CHAR(36) NOT NULL REFERENCES roboparts.web_sessions(primary_id) ON DELETE CASCADE,
    attribute_name VARCHAR(200) NOT NULL,
    attribute_bytes BYTEA NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);

CREATE TABLE roboparts.auth_attempt_locks (
    scope_id VARCHAR(16) PRIMARY KEY CHECK (scope_id IN ('account','ip'))
);
INSERT INTO roboparts.auth_attempt_locks (scope_id) VALUES ('account'), ('ip');
CREATE TABLE roboparts.auth_attempt_windows (
    scope_id VARCHAR(16) NOT NULL REFERENCES roboparts.auth_attempt_locks(scope_id),
    identity_hash CHAR(64) NOT NULL,
    generation UUID NOT NULL,
    expires_at BIGINT NOT NULL,
    used INTEGER NOT NULL CHECK (used > 0),
    PRIMARY KEY (scope_id, identity_hash)
);
CREATE INDEX auth_attempt_expiry ON roboparts.auth_attempt_windows (scope_id, expires_at);
