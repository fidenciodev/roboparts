-- Isolated H2-only schema fixture for JPA and servlet integration tests.
-- This does not execute or verify Flyway migrations or PostgreSQL audit triggers.
CREATE SCHEMA IF NOT EXISTS roboparts;
CREATE TABLE IF NOT EXISTS roboparts.application_metadata (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    application_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO roboparts.application_metadata (id, application_name)
SELECT 1, 'RoboParts' WHERE NOT EXISTS (SELECT 1 FROM roboparts.application_metadata WHERE id = 1);
CREATE TABLE IF NOT EXISTS roboparts.users (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT users_email_key UNIQUE (email)
);
CREATE TABLE IF NOT EXISTS roboparts.audit_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES roboparts.users(id),
    action VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
ALTER TABLE roboparts.audit_events ADD COLUMN IF NOT EXISTS details VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE roboparts.audit_events ADD COLUMN IF NOT EXISTS resource_type VARCHAR(40);
ALTER TABLE roboparts.audit_events ADD COLUMN IF NOT EXISTS resource_id UUID;
CREATE INDEX IF NOT EXISTS audit_events_created_idx ON roboparts.audit_events (created_at DESC, id);
CREATE TABLE IF NOT EXISTS roboparts.robots (
 id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 100),
 description VARCHAR(1000) NOT NULL DEFAULT '', archived BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS roboparts.component_nodes (
 id UUID PRIMARY KEY, robot_id UUID NOT NULL REFERENCES roboparts.robots(id),
 parent_id UUID, kind VARCHAR(20) NOT NULL CHECK (kind IN ('CATEGORY','COMPONENT')),
 name VARCHAR(100) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 100),
 description VARCHAR(1000) NOT NULL DEFAULT '', quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000000),
 required BOOLEAN NOT NULL, position INTEGER NOT NULL CHECK (position >= 0), archived BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE(robot_id,id), FOREIGN KEY(robot_id,parent_id) REFERENCES roboparts.component_nodes(robot_id,id),
 CHECK(parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX IF NOT EXISTS component_nodes_robot_idx ON roboparts.component_nodes(robot_id, parent_id, position);
CREATE TABLE IF NOT EXISTS roboparts.checklists (
 id UUID PRIMARY KEY, robot_id UUID NOT NULL REFERENCES roboparts.robots(id), request_id UUID NOT NULL,
 robot_name VARCHAR(100) NOT NULL, robot_description VARCHAR(1000) NOT NULL,
 started_by UUID NOT NULL REFERENCES roboparts.users(id),
 status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','RETURNING','RETURNED','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 finalized_at TIMESTAMP WITH TIME ZONE, finalized_by UUID REFERENCES roboparts.users(id),
 UNIQUE(robot_id,request_id)
);
CREATE INDEX IF NOT EXISTS checklists_robot_created_idx ON roboparts.checklists(robot_id,created_at DESC);
CREATE TABLE IF NOT EXISTS roboparts.checklist_items (
 id UUID PRIMARY KEY, checklist_id UUID NOT NULL REFERENCES roboparts.checklists(id), source_node_id UUID NOT NULL,
 parent_id UUID, kind VARCHAR(20) NOT NULL CHECK (kind IN ('CATEGORY','COMPONENT')),
 name VARCHAR(100) NOT NULL, description VARCHAR(1000) NOT NULL,
 quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000000), required BOOLEAN NOT NULL,
 position INTEGER NOT NULL CHECK (position >= 0),
 taken_quantity INTEGER NOT NULL DEFAULT 0 CHECK (taken_quantity >= 0 AND taken_quantity <= quantity),
 checked_by UUID REFERENCES roboparts.users(id), checked_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(checklist_id,id), FOREIGN KEY(checklist_id,parent_id) REFERENCES roboparts.checklist_items(checklist_id,id)
);
CREATE INDEX IF NOT EXISTS checklist_items_checklist_idx ON roboparts.checklist_items(checklist_id,position);
CREATE TABLE IF NOT EXISTS roboparts.movements (
 id UUID PRIMARY KEY, checklist_id UUID NOT NULL REFERENCES roboparts.checklists(id),
 item_id UUID NOT NULL, request_id UUID NOT NULL, user_id UUID NOT NULL REFERENCES roboparts.users(id),
 delta INTEGER NOT NULL CHECK(delta <> 0), resulting_quantity INTEGER NOT NULL CHECK(resulting_quantity >= 0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(checklist_id,request_id),
 FOREIGN KEY(checklist_id,item_id) REFERENCES roboparts.checklist_items(checklist_id,id)
);
CREATE INDEX IF NOT EXISTS movements_checklist_created_idx ON roboparts.movements(checklist_id,created_at,id);
ALTER TABLE roboparts.users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE IF NOT EXISTS roboparts.email_verifications (
 id UUID PRIMARY KEY, user_id UUID NOT NULL UNIQUE REFERENCES roboparts.users(id),
 code_hash VARCHAR(255) NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL, attempts INTEGER NOT NULL CHECK (attempts BETWEEN 0 AND 5),
 send_window_at TIMESTAMP WITH TIME ZONE NOT NULL, send_count INTEGER NOT NULL CHECK (send_count BETWEEN 1 AND 5)
);
ALTER TABLE roboparts.users ADD COLUMN IF NOT EXISTS password_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS roboparts.password_resets (
 user_id UUID PRIMARY KEY REFERENCES roboparts.users(id), code_hash VARCHAR(255) NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, last_sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
 attempts INTEGER NOT NULL CHECK (attempts BETWEEN 0 AND 5), send_window_at TIMESTAMP WITH TIME ZONE NOT NULL,
 send_count INTEGER NOT NULL CHECK (send_count BETWEEN 1 AND 5)
);
ALTER TABLE roboparts.users ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE IF NOT EXISTS roboparts.team_invitations (
 id UUID PRIMARY KEY, email VARCHAR(254) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE,
 created_by UUID NOT NULL REFERENCES roboparts.users(id), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, consumed_at TIMESTAMP WITH TIME ZONE, revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS roboparts.security_events (
 id UUID PRIMARY KEY,event_type VARCHAR(40) NOT NULL,account_key VARCHAR(64) NOT NULL,created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS roboparts.mfa_credentials (
 user_id UUID PRIMARY KEY REFERENCES roboparts.users(id),encrypted_secret VARCHAR(512) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT FALSE,
 last_counter BIGINT NOT NULL DEFAULT -1,enrollment_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 failure_window_at TIMESTAMP WITH TIME ZONE NOT NULL,failures INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS roboparts.mfa_recovery_codes (
 user_id UUID NOT NULL REFERENCES roboparts.users(id),code_hash VARCHAR(64) NOT NULL,used_at TIMESTAMP WITH TIME ZONE,PRIMARY KEY(user_id,code_hash)
);
