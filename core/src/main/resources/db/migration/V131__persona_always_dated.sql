-- Every agent is told the current date; the per-agent switch that could leave it out is gone.
ALTER TABLE persona DROP COLUMN datetime_aware;
