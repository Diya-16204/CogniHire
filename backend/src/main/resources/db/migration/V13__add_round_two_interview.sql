ALTER TABLE assessment_attempts
  DROP CONSTRAINT IF EXISTS assessment_attempts_status_check;

ALTER TABLE assessment_attempts
  ADD CONSTRAINT assessment_attempts_status_check
    CHECK (status IN ('IN_PROGRESS','ROUND_2_COMPLETED','SUBMITTED','EXPIRED')),
  ADD COLUMN round_two_interview_transcript JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN round_two_interview_completed_at TIMESTAMPTZ;

CREATE TABLE round_two_interview_turns (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES assessment_attempts(id) ON DELETE CASCADE,
  turn_index INTEGER NOT NULL CHECK (turn_index >= 0),
  subject VARCHAR(120) NOT NULL,
  prompt TEXT NOT NULL,
  answer TEXT NOT NULL,
  answered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_round_two_interview_turn UNIQUE (attempt_id, turn_index)
);

CREATE INDEX idx_round_two_interview_attempt
  ON round_two_interview_turns(attempt_id, turn_index);
