-- MEM-273: an assistant gains a role line, a greeting for an empty conversation and its own reasoning level, and
-- loses five fields no production assistant uses.
ALTER TABLE persona
    ADD COLUMN title VARCHAR(80),
    ADD COLUMN greeting VARCHAR(500),
    ADD COLUMN reasoning_effort VARCHAR(16)
        CONSTRAINT ck_persona_reasoning_effort
        CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('OFF', 'LOW', 'MEDIUM', 'HIGH'));

-- The task prompt was a reminder after every message; it stays in the instructions after a blank line. Instructions
-- hold at most 32000 characters, so a fold that would exceed them keeps the first 32000.
UPDATE persona
SET instructions = left(CASE WHEN instructions ~ '^\s*$' THEN regexp_replace(task_prompt, '^\s+|\s+$', '', 'g')
                             ELSE regexp_replace(instructions, '\s+$', '') || E'\n\n'
                                  || regexp_replace(task_prompt, '^\s+|\s+$', '', 'g') END, 32000),
    revision = revision + 1
WHERE task_prompt !~ '^\s*$';

-- The editor bounds named the two token limits; dropping them would drop the whole constraint, so it is replaced.
ALTER TABLE persona DROP CONSTRAINT ck_persona_editor_bounds;
ALTER TABLE persona
    DROP COLUMN task_prompt,
    DROP COLUMN knowledge_cutoff,
    DROP COLUMN context_token_limit,
    DROP COLUMN output_token_limit,
    DROP COLUMN replace_base_system_prompt;
ALTER TABLE persona ADD CONSTRAINT ck_persona_editor_bounds CHECK
    (length(trim(name)) > 0 AND length(instructions) <= 32000 AND revision >= 0);
