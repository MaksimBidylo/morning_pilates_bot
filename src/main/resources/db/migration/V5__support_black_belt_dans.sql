ALTER TABLE athlete_profiles DROP CONSTRAINT athlete_profiles_stripes_check;

ALTER TABLE athlete_profiles ADD CONSTRAINT athlete_profiles_rank_marks_check
    CHECK (stripes BETWEEN 0 AND 10 AND (stripes <= 4 OR belt IS NOT DISTINCT FROM 'Black'));
