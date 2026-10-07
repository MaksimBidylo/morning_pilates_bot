CREATE TABLE database_probe (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO database_probe (value) VALUES ('migrated');
