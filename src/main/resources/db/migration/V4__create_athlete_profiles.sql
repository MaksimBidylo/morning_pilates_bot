CREATE TABLE athlete_profiles (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    birth_date DATE,
    weight NUMERIC(5, 2) CHECK (weight > 0 AND weight <= 500),
    belt VARCHAR(16) CHECK (belt IN ('White', 'Blue', 'Purple', 'Brown', 'Black', 'Coral', 'Red')),
    stripes INTEGER CHECK (stripes BETWEEN 0 AND 4),
    belt_date DATE,
    bjj_start DATE CHECK (EXTRACT(DAY FROM bjj_start) = 1)
);
