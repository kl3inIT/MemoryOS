-- Optional telemetry origin; does not participate in dispatch or processing authority.
ALTER TABLE index_attempts ADD COLUMN origin_trace_id VARCHAR(32);
ALTER TABLE index_attempts ADD COLUMN origin_span_id VARCHAR(16);
ALTER TABLE connector_cleanup_attempts ADD COLUMN origin_trace_id VARCHAR(32);
ALTER TABLE connector_cleanup_attempts ADD COLUMN origin_span_id VARCHAR(16);
