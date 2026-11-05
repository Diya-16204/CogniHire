ALTER TABLE assessments
  ADD COLUMN github_analysis_enabled BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN github_repository_url VARCHAR(500),
  ADD COLUMN project_description TEXT;
