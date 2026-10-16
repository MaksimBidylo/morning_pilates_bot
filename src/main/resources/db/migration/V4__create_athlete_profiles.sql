CREATE TABLE athlete_profiles (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    birth_date DATE,
    weight NUMERIC(5, 2) CHECK (weight > 0 AND weight <= 150),
    belt VARCHAR(16) CHECK (belt IN ('White', 'Blue', 'Purple', 'Brown', 'Black', 'Coral', 'Red')),
    rank_marks INTEGER CHECK (
        rank_marks BETWEEN 0 AND 10 AND (rank_marks <= 4 OR belt IS NOT DISTINCT FROM 'Black')
    ),
    belt_date DATE CHECK (belt_date IS NULL OR belt IS NOT NULL),
    bjj_start DATE CHECK (EXTRACT(DAY FROM bjj_start) = 1)
);
