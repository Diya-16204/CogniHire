CREATE TABLE recruiter_interventions (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES assessment_attempts(id) ON DELETE CASCADE,
  recruiter_id UUID NOT NULL REFERENCES users(id),
  question TEXT NOT NULL,
  answer TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  answered_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_recruiter_interventions_attempt_created
  ON recruiter_interventions (attempt_id, created_at);
