ALTER TABLE roboparts.users ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE roboparts.team_invitations (
 id UUID PRIMARY KEY, email VARCHAR(254) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE,
 created_by UUID NOT NULL REFERENCES roboparts.users(id), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, consumed_at TIMESTAMP WITH TIME ZONE, revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX team_invitations_email_idx ON roboparts.team_invitations(email);
CREATE TABLE roboparts.security_events (
 id UUID PRIMARY KEY, event_type VARCHAR(40) NOT NULL, account_key VARCHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX security_events_created_idx ON roboparts.security_events(created_at);
