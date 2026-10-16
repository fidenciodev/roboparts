-- Existing accounts must also confirm ownership on their next login.
ALTER TABLE roboparts.users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE roboparts.email_verifications (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL UNIQUE REFERENCES roboparts.users(id),
 code_hash VARCHAR(255) NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
 attempts INTEGER NOT NULL CHECK (attempts BETWEEN 0 AND 5),
 send_window_at TIMESTAMP WITH TIME ZONE NOT NULL,
 send_count INTEGER NOT NULL CHECK (send_count BETWEEN 1 AND 5)
);
