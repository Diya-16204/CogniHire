CREATE TABLE round_two_interview_questions (
  id UUID PRIMARY KEY,
  assessment_id UUID NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,
  subject VARCHAR(120) NOT NULL,
  question_text TEXT NOT NULL,
  position INTEGER NOT NULL CHECK (position >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_round_two_interview_question_position UNIQUE (assessment_id, position)
);

CREATE INDEX idx_round_two_interview_question_assessment
  ON round_two_interview_questions(assessment_id, position, created_at);
