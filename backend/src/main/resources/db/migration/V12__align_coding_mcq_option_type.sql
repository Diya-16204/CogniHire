ALTER TABLE coding_attempt_mcq_answers
  ALTER COLUMN selected_option TYPE VARCHAR(1)
  USING selected_option::VARCHAR(1);
