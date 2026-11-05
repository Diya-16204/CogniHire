ALTER TABLE assessment_attempts
  ALTER COLUMN adjusted_score TYPE DOUBLE PRECISION
  USING adjusted_score::DOUBLE PRECISION;
