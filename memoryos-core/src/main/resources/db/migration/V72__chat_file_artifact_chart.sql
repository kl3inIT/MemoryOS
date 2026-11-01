-- Chart data captured from a matplotlib figure run_python left open (E2B chart model); the PNG is the artifact itself.
ALTER TABLE chat_file_artifact ADD COLUMN chart jsonb
    CHECK (chart IS NULL OR (jsonb_typeof(chart) = 'object' AND octet_length(chart::text) <= 262144));
