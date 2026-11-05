ALTER TABLE assessments
  ADD COLUMN role VARCHAR(160) NOT NULL DEFAULT 'General',
  ADD COLUMN passing_score NUMERIC(5,2) NOT NULL DEFAULT 60.00,
  ADD COLUMN test_code VARCHAR(6);

ALTER TABLE assessments
  ADD CONSTRAINT chk_assessment_passing_score CHECK (passing_score BETWEEN 0 AND 100),
  ADD CONSTRAINT uq_assessment_test_code UNIQUE (test_code);

CREATE TABLE assessment_mcq_questions (
  id UUID PRIMARY KEY,
  assessment_id UUID NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,
  question_text TEXT NOT NULL,
  option_a VARCHAR(1000) NOT NULL,
  option_b VARCHAR(1000) NOT NULL,
  option_c VARCHAR(1000) NOT NULL,
  option_d VARCHAR(1000) NOT NULL,
  correct_option CHAR(1) NOT NULL CHECK (correct_option IN ('A','B','C','D')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mcq_question_assessment ON assessment_mcq_questions(assessment_id, created_at, id);

CREATE TABLE assessment_mcq_attempts (
  id UUID PRIMARY KEY,
  assessment_id UUID NOT NULL REFERENCES assessments(id),
  candidate_name VARCHAR(160) NOT NULL,
  candidate_email VARCHAR(320) NOT NULL,
  score INTEGER,
  percentage NUMERIC(5,2),
  status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED','SHORTLISTED','REJECTED')),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  submitted_at TIMESTAMPTZ,
  CONSTRAINT uq_mcq_attempt_candidate UNIQUE (assessment_id, candidate_email)
);
CREATE INDEX idx_mcq_attempt_assessment ON assessment_mcq_attempts(assessment_id, started_at DESC);

CREATE TABLE assessment_mcq_attempt_answers (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES assessment_mcq_attempts(id) ON DELETE CASCADE,
  question_id UUID NOT NULL REFERENCES assessment_mcq_questions(id),
  selected_option CHAR(1) CHECK (selected_option IS NULL OR selected_option IN ('A','B','C','D')),
  is_correct BOOLEAN NOT NULL,
  CONSTRAINT uq_mcq_answer_attempt_question UNIQUE (attempt_id, question_id)
);
CREATE INDEX idx_mcq_answer_attempt ON assessment_mcq_attempt_answers(attempt_id);
