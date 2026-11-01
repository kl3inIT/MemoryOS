-- The heading of a biên bản (organization, number, place, chair, secretary, font) as the owner last left it, so the
-- export dialog opens where they stopped instead of blank. NULL until the owner saves one.
ALTER TABLE meeting ADD COLUMN minutes_heading JSONB;
