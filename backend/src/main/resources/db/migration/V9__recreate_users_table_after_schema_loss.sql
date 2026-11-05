-- V8 may already be recorded even if the users table was removed afterward.
-- Recreate the account table so Hibernate can start; existing account data cannot
-- be recovered when the table itself is absent.
CREATE TABLE IF NOT EXISTS public.users (
  id UUID PRIMARY KEY,
  email VARCHAR(320) NOT NULL UNIQUE,
  full_name VARCHAR(160) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL CHECK (role IN ('RECRUITER','CANDIDATE','ADMIN')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Also repair the specific partial-table state reported by Hibernate.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS id UUID;
UPDATE public.users SET id = gen_random_uuid() WHERE id IS NULL;
ALTER TABLE public.users ALTER COLUMN id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS users_id_uidx ON public.users(id);

DO $$
BEGIN
  IF to_regclass('public.assessments') IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'fk_assessments_owner_users'
      AND conrelid = 'public.assessments'::regclass
  ) THEN
    ALTER TABLE public.assessments ADD CONSTRAINT fk_assessments_owner_users
      FOREIGN KEY (owner_id) REFERENCES public.users(id) NOT VALID;
  END IF;

  IF to_regclass('public.assessment_assignments') IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'fk_assignments_candidate_users'
      AND conrelid = 'public.assessment_assignments'::regclass
  ) THEN
    ALTER TABLE public.assessment_assignments ADD CONSTRAINT fk_assignments_candidate_users
      FOREIGN KEY (candidate_id) REFERENCES public.users(id) NOT VALID;
  END IF;

  IF to_regclass('public.assessment_attempts') IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'fk_attempts_candidate_users'
      AND conrelid = 'public.assessment_attempts'::regclass
  ) THEN
    ALTER TABLE public.assessment_attempts ADD CONSTRAINT fk_attempts_candidate_users
      FOREIGN KEY (candidate_id) REFERENCES public.users(id) NOT VALID;
  END IF;
END $$;
