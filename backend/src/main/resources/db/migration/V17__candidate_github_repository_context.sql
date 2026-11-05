ALTER TABLE assessment_attempts
  ADD COLUMN github_repository_url VARCHAR(500),
  ADD COLUMN round_two_interview_questions JSONB NOT NULL DEFAULT '[]'::jsonb;
