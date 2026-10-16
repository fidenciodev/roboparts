ALTER TABLE roboparts.users ADD COLUMN password_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE roboparts.password_resets (
 user_id UUID PRIMARY KEY REFERENCES roboparts.users(id),
 code_hash VARCHAR(255) NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
 attempts INTEGER NOT NULL CHECK (attempts BETWEEN 0 AND 5),
 send_window_at TIMESTAMP WITH TIME ZONE NOT NULL,
 send_count INTEGER NOT NULL CHECK (send_count BETWEEN 1 AND 5)
);
