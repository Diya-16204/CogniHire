ALTER TABLE ai_interactions
  ALTER COLUMN penalty_fraction TYPE DOUBLE PRECISION
  USING penalty_fraction::DOUBLE PRECISION;
