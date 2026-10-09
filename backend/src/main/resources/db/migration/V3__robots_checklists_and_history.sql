ALTER TABLE roboparts.audit_events ADD COLUMN details VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE roboparts.audit_events ADD COLUMN resource_type VARCHAR(40);
ALTER TABLE roboparts.audit_events ADD COLUMN resource_id UUID;
CREATE INDEX audit_events_created_idx ON roboparts.audit_events (created_at DESC, id);
CREATE TABLE roboparts.robots (
 id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 100),
 description VARCHAR(1000) NOT NULL DEFAULT '', archived BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE roboparts.component_nodes (
 id UUID PRIMARY KEY, robot_id UUID NOT NULL REFERENCES roboparts.robots(id),
 parent_id UUID, kind VARCHAR(20) NOT NULL CHECK (kind IN ('CATEGORY','COMPONENT')),
 name VARCHAR(100) NOT NULL CHECK (char_length(btrim(name)) BETWEEN 2 AND 100),
 description VARCHAR(1000) NOT NULL DEFAULT '', quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000000),
 required BOOLEAN NOT NULL, position INTEGER NOT NULL CHECK (position >= 0), archived BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE(robot_id,id), FOREIGN KEY(robot_id,parent_id) REFERENCES roboparts.component_nodes(robot_id,id),
 CHECK(parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX component_nodes_robot_idx ON roboparts.component_nodes(robot_id, parent_id, position);
CREATE TABLE roboparts.checklists (
 id UUID PRIMARY KEY, robot_id UUID NOT NULL REFERENCES roboparts.robots(id), request_id UUID NOT NULL,
 robot_name VARCHAR(100) NOT NULL, robot_description VARCHAR(1000) NOT NULL,
 started_by UUID NOT NULL REFERENCES roboparts.users(id),
 status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','RETURNING','RETURNED','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 finalized_at TIMESTAMP WITH TIME ZONE, finalized_by UUID REFERENCES roboparts.users(id),
 UNIQUE(robot_id,request_id)
);
CREATE INDEX checklists_robot_created_idx ON roboparts.checklists(robot_id,created_at DESC);
CREATE TABLE roboparts.checklist_items (
 id UUID PRIMARY KEY, checklist_id UUID NOT NULL REFERENCES roboparts.checklists(id), source_node_id UUID NOT NULL,
 parent_id UUID, kind VARCHAR(20) NOT NULL CHECK (kind IN ('CATEGORY','COMPONENT')),
 name VARCHAR(100) NOT NULL, description VARCHAR(1000) NOT NULL,
 quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000000), required BOOLEAN NOT NULL,
 position INTEGER NOT NULL CHECK (position >= 0),
 taken_quantity INTEGER NOT NULL DEFAULT 0 CHECK (taken_quantity >= 0 AND taken_quantity <= quantity),
 checked_by UUID REFERENCES roboparts.users(id), checked_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(checklist_id,id), FOREIGN KEY(checklist_id,parent_id) REFERENCES roboparts.checklist_items(checklist_id,id)
);
CREATE INDEX checklist_items_checklist_idx ON roboparts.checklist_items(checklist_id,position);
CREATE TABLE roboparts.movements (
 id UUID PRIMARY KEY, checklist_id UUID NOT NULL REFERENCES roboparts.checklists(id),
 item_id UUID NOT NULL, request_id UUID NOT NULL, user_id UUID NOT NULL REFERENCES roboparts.users(id),
 delta INTEGER NOT NULL CHECK(delta <> 0), resulting_quantity INTEGER NOT NULL CHECK(resulting_quantity >= 0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, UNIQUE(checklist_id,request_id),
 FOREIGN KEY(checklist_id,item_id) REFERENCES roboparts.checklist_items(checklist_id,id)
);
CREATE INDEX movements_checklist_created_idx ON roboparts.movements(checklist_id,created_at,id);
-- Snapshot identity and structure cannot be overwritten after insertion.
CREATE FUNCTION roboparts.protect_checklist_snapshot() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'Snapshots não podem ser removidos.' USING ERRCODE='55000'; END IF;
 IF ROW(NEW.checklist_id,NEW.source_node_id,NEW.parent_id,NEW.kind,NEW.name,NEW.description,NEW.quantity,NEW.required,NEW.position)
 IS DISTINCT FROM ROW(OLD.checklist_id,OLD.source_node_id,OLD.parent_id,OLD.kind,OLD.name,OLD.description,OLD.quantity,OLD.required,OLD.position)
 THEN RAISE EXCEPTION 'A estrutura do snapshot é imutável.' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER checklist_snapshot_immutable BEFORE UPDATE OR DELETE ON roboparts.checklist_items
 FOR EACH ROW EXECUTE FUNCTION roboparts.protect_checklist_snapshot();
CREATE TRIGGER checklist_snapshot_no_truncate BEFORE TRUNCATE ON roboparts.checklist_items
 FOR EACH STATEMENT EXECUTE FUNCTION roboparts.reject_audit_event_mutation();
CREATE TRIGGER movements_immutable BEFORE UPDATE OR DELETE ON roboparts.movements
 FOR EACH ROW EXECUTE FUNCTION roboparts.reject_audit_event_mutation();
CREATE TRIGGER movements_no_truncate BEFORE TRUNCATE ON roboparts.movements
 FOR EACH STATEMENT EXECUTE FUNCTION roboparts.reject_audit_event_mutation();
