CREATE TABLE users (
  id UUID PRIMARY KEY,
  email VARCHAR(320) NOT NULL UNIQUE,
  full_name VARCHAR(160) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL CHECK (role IN ('RECRUITER','CANDIDATE','ADMIN')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE assessments (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES users(id),
  title VARCHAR(180) NOT NULL,
  description TEXT NOT NULL,
  duration_minutes INTEGER NOT NULL CHECK (duration_minutes BETWEEN 1 AND 600),
  status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
  ai_enabled BOOLEAN NOT NULL DEFAULT false,
  allowed_assistance JSONB NOT NULL DEFAULT '[]'::jsonb,
  max_hints_per_attempt INTEGER NOT NULL DEFAULT 0 CHECK (max_hints_per_attempt BETWEEN 0 AND 50),
  max_penalty DECIMAL(5,4) NOT NULL DEFAULT 0.30 CHECK (max_penalty BETWEEN 0 AND 0.30),
  version INTEGER NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_assessment_owner ON assessments(owner_id, updated_at DESC);
CREATE TABLE coding_questions (
  id UUID PRIMARY KEY,
  assessment_id UUID NOT NULL REFERENCES assessments(id) ON DELETE CASCADE,
  title VARCHAR(180) NOT NULL,
  statement TEXT NOT NULL,
  difficulty VARCHAR(20) NOT NULL,
  points INTEGER NOT NULL CHECK (points > 0),
  position INTEGER NOT NULL,
  public_samples JSONB NOT NULL DEFAULT '[]'::jsonb,
  hidden_tests JSONB NOT NULL DEFAULT '[]'::jsonb,
  reference_solution TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(assessment_id, position)
);
CREATE INDEX idx_question_assessment ON coding_questions(assessment_id, position);
CREATE TABLE assessment_assignments (
  id UUID PRIMARY KEY,
  assessment_id UUID NOT NULL REFERENCES assessments(id),
  candidate_id UUID NOT NULL REFERENCES users(id),
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(assessment_id,candidate_id)
);
CREATE TABLE assessment_attempts (
  id UUID PRIMARY KEY,
  assignment_id UUID NOT NULL REFERENCES assessment_assignments(id),
  candidate_id UUID NOT NULL REFERENCES users(id),
  status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS','SUBMITTED','EXPIRED')),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deadline_at TIMESTAMPTZ NOT NULL,
  policy_snapshot JSONB NOT NULL,
  question_snapshot JSONB NOT NULL,
  raw_score DECIMAL(7,2),
  adjusted_score DECIMAL(7,2)
);
CREATE UNIQUE INDEX uq_attempt_assignment ON assessment_attempts(assignment_id);
CREATE INDEX idx_attempt_candidate ON assessment_attempts(candidate_id, started_at DESC);
CREATE TABLE code_submissions (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES assessment_attempts(id),
  question_id UUID NOT NULL REFERENCES coding_questions(id),
  language VARCHAR(40) NOT NULL,
  source_code TEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  provider_token VARCHAR(200),
  result JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_submission_attempt ON code_submissions(attempt_id, created_at DESC);
CREATE TABLE ai_interactions (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES assessment_attempts(id),
  question_id UUID NOT NULL REFERENCES coding_questions(id),
  category VARCHAR(40) NOT NULL,
  request_text TEXT NOT NULL,
  response_text TEXT NOT NULL,
  delivered BOOLEAN NOT NULL,
  penalty_fraction DECIMAL(5,4) NOT NULL DEFAULT 0,
  disclosure_key VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_interaction_attempt_question ON ai_interactions(attempt_id,question_id,created_at DESC);
CREATE UNIQUE INDEX uq_ai_delivered_disclosure ON ai_interactions(attempt_id,question_id,disclosure_key) WHERE delivered = true;
