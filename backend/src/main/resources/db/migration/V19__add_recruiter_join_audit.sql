ALTER TABLE assessment_attempts
  ADD COLUMN recruiter_joined_during_test BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN recruiter_joined_at TIMESTAMP WITHOUT TIME ZONE;
