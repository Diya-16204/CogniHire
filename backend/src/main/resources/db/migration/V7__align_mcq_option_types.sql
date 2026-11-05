ALTER TABLE assessment_mcq_questions
  ALTER COLUMN correct_option TYPE VARCHAR(1)
  USING correct_option::VARCHAR(1);

ALTER TABLE assessment_mcq_attempt_answers
  ALTER COLUMN selected_option TYPE VARCHAR(1)
  USING selected_option::VARCHAR(1);
