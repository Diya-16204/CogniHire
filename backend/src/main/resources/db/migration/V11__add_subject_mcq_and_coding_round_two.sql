ALTER TABLE assessment_mcq_questions
  ADD COLUMN subject VARCHAR(120) NOT NULL DEFAULT 'General';

ALTER TABLE assessment_attempts
  ADD COLUMN round_two_started_at TIMESTAMPTZ,
  ADD COLUMN round_two_score INTEGER,
  ADD COLUMN round_two_total INTEGER;

CREATE TABLE coding_attempt_mcq_answers (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES assessment_attempts(id) ON DELETE CASCADE,
  question_id UUID NOT NULL REFERENCES assessment_mcq_questions(id),
  selected_option CHAR(1) NOT NULL CHECK (selected_option IN ('A','B','C','D')),
  is_correct BOOLEAN NOT NULL,
  answered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_coding_mcq_attempt_question UNIQUE (attempt_id, question_id)
);

CREATE INDEX idx_coding_mcq_attempt ON coding_attempt_mcq_answers(attempt_id, answered_at, id);
