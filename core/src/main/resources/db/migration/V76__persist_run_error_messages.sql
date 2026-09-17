-- Bounded failure evidence: a stable code stays the machine contract; message/detail carry
-- the provider or extraction cause for operators. Historical rows keep NULL.
ALTER TABLE source_run_errors
    ADD COLUMN error_message VARCHAR(512),
    ADD COLUMN error_detail VARCHAR(4096);

ALTER TABLE index_attempts
    ADD COLUMN error_message VARCHAR(512),
    ADD COLUMN error_detail VARCHAR(4096);

ALTER TABLE source_sync_attempts
    ADD COLUMN error_message VARCHAR(512),
    ADD COLUMN error_detail VARCHAR(4096);

ALTER TABLE connector_cleanup_attempts
    ADD COLUMN error_message VARCHAR(512),
    ADD COLUMN error_detail VARCHAR(4096);

ALTER TABLE google_drive_acl_snapshots
    ADD COLUMN error_message VARCHAR(512);

ALTER TABLE chat_file_work
    ADD COLUMN error_message VARCHAR(512),
    ADD COLUMN error_detail VARCHAR(4096);

-- Indexing failure rows copy the attempt's message/detail into run history.
CREATE OR REPLACE FUNCTION source_run_index_transition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.source_sync_attempt_id IS DISTINCT FROM OLD.source_sync_attempt_id THEN
        RAISE EXCEPTION 'Index run attribution is immutable';
    END IF;
    IF NEW.source_sync_attempt_id IS NULL THEN RETURN NEW; END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('NOT_STARTED', 'IN_PROGRESS') THEN
            RAISE EXCEPTION 'Attributed indexing must begin pending';
        END IF;
        UPDATE source_sync_attempts SET indexing_pending = indexing_pending + 1
        WHERE tenant_id = NEW.tenant_id AND id = NEW.source_sync_attempt_id
            AND status IN ('NOT_STARTED', 'IN_PROGRESS') AND run_completed_at IS NULL;
        IF NOT FOUND THEN RAISE EXCEPTION 'Cannot attribute indexing after acquisition closes'; END IF;
    ELSIF OLD.status NOT IN ('NOT_STARTED', 'IN_PROGRESS') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'Closed run indexing outcomes are immutable';
    ELSIF OLD.status IN ('NOT_STARTED', 'IN_PROGRESS') AND NEW.status NOT IN ('NOT_STARTED', 'IN_PROGRESS') THEN
        UPDATE source_sync_attempts SET
            indexing_pending = indexing_pending - 1,
            published = published + CASE WHEN NEW.status = 'SUCCEEDED' THEN 1 ELSE 0 END,
            indexing_failed = indexing_failed + CASE WHEN NEW.status = 'FAILED' THEN 1 ELSE 0 END,
            indexing_superseded = indexing_superseded + CASE WHEN NEW.status = 'SUPERSEDED' THEN 1 ELSE 0 END,
            indexing_cancelled = indexing_cancelled + CASE WHEN NEW.status = 'CANCELLED' THEN 1 ELSE 0 END
        WHERE tenant_id = NEW.tenant_id AND id = NEW.source_sync_attempt_id;
        IF NEW.status = 'FAILED' THEN
            INSERT INTO source_run_errors (id, tenant_id, run_id, error_key, operation_id, item_id,
                file_id, file_name, stage, code, error_message, error_detail)
            SELECT gen_random_uuid(), NEW.tenant_id, NEW.source_sync_attempt_id, 'INDEX:' || NEW.id,
                NEW.id, NEW.connector_item_id, v.provider_file_id, o.filename,
                source_run_error_stage(COALESCE(NEW.error_code, 'SOURCE_EXTRACTION_INTERNAL')),
                COALESCE(NEW.error_code, 'SOURCE_EXTRACTION_INTERNAL'),
                NEW.error_message, NEW.error_detail
            FROM connector_item_versions v JOIN stored_objects o ON o.tenant_id = v.tenant_id AND o.id = v.stored_object_id
            WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.connector_item_version_id
            ON CONFLICT (tenant_id, run_id, error_key) DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- Run-level acquisition failures copy the attempt's message/detail as well.
CREATE OR REPLACE FUNCTION source_run_acquisition_error() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.history_version = 1 AND NEW.status = 'FAILED' AND OLD.status IN ('NOT_STARTED', 'IN_PROGRESS')
        AND NEW.error_code IS NOT NULL THEN
        INSERT INTO source_run_errors (id, tenant_id, run_id, error_key, operation_id, stage, code,
            error_message, error_detail)
        VALUES (gen_random_uuid(), NEW.tenant_id, NEW.id, 'RUN', NEW.id,
            source_run_error_stage(NEW.error_code), NEW.error_code, NEW.error_message, NEW.error_detail)
        ON CONFLICT (tenant_id, run_id, error_key) DO NOTHING;
    END IF;
    RETURN NEW;
END
$$;
