-- New employee and authentication activity tables. No changes to V1 or existing records.
CREATE TABLE roboparts.users (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 100),
    email VARCHAR(254) NOT NULL CHECK (email = lower(btrim(email)) AND char_length(email) > 0),
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_email_key UNIQUE (email)
);

CREATE TABLE roboparts.audit_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES roboparts.users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    action VARCHAR(40) NOT NULL CHECK (char_length(action) > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX audit_events_user_created_idx ON roboparts.audit_events (user_id, created_at DESC);

CREATE FUNCTION roboparts.reject_audit_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Registros de auditoria não podem ser alterados ou removidos.'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER audit_events_immutable
BEFORE UPDATE OR DELETE ON roboparts.audit_events
FOR EACH ROW EXECUTE FUNCTION roboparts.reject_audit_event_mutation();

CREATE TRIGGER audit_events_no_truncate
BEFORE TRUNCATE ON roboparts.audit_events
FOR EACH STATEMENT EXECUTE FUNCTION roboparts.reject_audit_event_mutation();
