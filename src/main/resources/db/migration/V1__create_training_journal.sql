CREATE TABLE users (
    id UUID PRIMARY KEY,
    telegram_id_encrypted BYTEA NOT NULL,
    telegram_id_lookup BYTEA NOT NULL UNIQUE
);

CREATE TABLE trainings (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    training_date DATE NOT NULL,
    start_time TIME NOT NULL,
    note TEXT
);

CREATE TABLE training_techniques (
    id UUID PRIMARY KEY,
    training_id UUID NOT NULL REFERENCES trainings (id),
    position INTEGER NOT NULL,
    name TEXT NOT NULL,
    description TEXT
);
