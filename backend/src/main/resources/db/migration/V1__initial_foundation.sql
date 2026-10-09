-- Only technical foundation data. Business tables arrive in later stages.
-- Flyway manages schema roboparts; existing public schema data is untouched.
CREATE TABLE roboparts.application_metadata (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    application_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO roboparts.application_metadata (id, application_name)
VALUES (1, 'RoboParts');
