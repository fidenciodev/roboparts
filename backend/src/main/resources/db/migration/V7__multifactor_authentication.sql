CREATE TABLE roboparts.mfa_credentials (
 user_id UUID PRIMARY KEY REFERENCES roboparts.users(id), encrypted_secret VARCHAR(512) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, last_counter BIGINT NOT NULL DEFAULT -1,
 enrollment_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 failure_window_at TIMESTAMP WITH TIME ZONE NOT NULL, failures INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE roboparts.mfa_recovery_codes (
 user_id UUID NOT NULL REFERENCES roboparts.users(id), code_hash VARCHAR(64) NOT NULL,
 used_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY(user_id,code_hash)
);
