-- How hard each task's model thinks, chosen beside the model on the Models page. Null is the task's own default
-- (ModelFlow.defaultEffort): medium for the meeting minutes, off for the helper tasks. Every existing row starts at
-- its default, so nothing a Tenant runs changes until an administrator picks a level.
ALTER TABLE model_flow_default ADD COLUMN reasoning_effort VARCHAR(8)
    CHECK (reasoning_effort IN ('OFF', 'LOW', 'MEDIUM', 'HIGH'));
