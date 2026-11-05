-- Recover the core account table if it was dropped manually after earlier migrations.
-- IF NOT EXISTS keeps this migration harmless on healthy databases.
CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY,
  email VARCHAR(320) NOT NULL UNIQUE,
  full_name VARCHAR(160) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL CHECK (role IN ('RECRUITER','CANDIDATE','ADMIN')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
    WHERE c.contype = 'f' AND c.conrelid = 'public.assessments'::regclass
      AND c.confrelid = 'public.users'::regclass AND a.attname = 'owner_id'
  ) THEN
    ALTER TABLE public.assessments ADD CONSTRAINT fk_assessments_owner_users
      FOREIGN KEY (owner_id) REFERENCES public.users(id) NOT VALID;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
    WHERE c.contype = 'f' AND c.conrelid = 'public.assessment_assignments'::regclass
      AND c.confrelid = 'public.users'::regclass AND a.attname = 'candidate_id'
  ) THEN
    ALTER TABLE public.assessment_assignments ADD CONSTRAINT fk_assignments_candidate_users
      FOREIGN KEY (candidate_id) REFERENCES public.users(id) NOT VALID;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
    WHERE c.contype = 'f' AND c.conrelid = 'public.assessment_attempts'::regclass
      AND c.confrelid = 'public.users'::regclass AND a.attname = 'candidate_id'
  ) THEN
    ALTER TABLE public.assessment_attempts ADD CONSTRAINT fk_attempts_candidate_users
      FOREIGN KEY (candidate_id) REFERENCES public.users(id) NOT VALID;
  END IF;
END $$;
