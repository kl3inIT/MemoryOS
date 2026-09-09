ALTER TABLE source_sync_attempts
    ADD COLUMN history_version SMALLINT CHECK (history_version = 1),
    ADD COLUMN trigger_kind VARCHAR(16) CHECK (trigger_kind IN ('SCHEDULED', 'MANUAL', 'INITIAL')),
    ADD COLUMN actor_id UUID REFERENCES actors(id) ON DELETE SET NULL,
    ADD COLUMN run_completed_at TIMESTAMPTZ,
    ADD COLUMN details_expired_at TIMESTAMPTZ,
    ADD COLUMN scanned BIGINT CHECK (scanned >= 0),
    ADD COLUMN acquired BIGINT CHECK (acquired >= 0),
    ADD COLUMN unchanged BIGINT CHECK (unchanged >= 0),
    ADD COLUMN already_pending BIGINT CHECK (already_pending >= 0),
    ADD COLUMN acquisition_failed BIGINT CHECK (acquisition_failed >= 0),
    ADD COLUMN skipped BIGINT CHECK (skipped >= 0),
    ADD COLUMN removed BIGINT CHECK (removed >= 0),
    ADD COLUMN published BIGINT CHECK (published >= 0),
    ADD COLUMN indexing_pending BIGINT CHECK (indexing_pending >= 0),
    ADD COLUMN indexing_failed BIGINT CHECK (indexing_failed >= 0),
    ADD COLUMN indexing_superseded BIGINT CHECK (indexing_superseded >= 0),
    ADD COLUMN indexing_cancelled BIGINT CHECK (indexing_cancelled >= 0),
    ADD CONSTRAINT uq_source_sync_tenant_source_id UNIQUE (tenant_id, source_id, id);

ALTER TABLE index_attempts ADD COLUMN source_sync_attempt_id UUID,
    ADD CONSTRAINT fk_index_attempt_source_run
        FOREIGN KEY (tenant_id, connector_credential_pair_id, source_sync_attempt_id)
        REFERENCES source_sync_attempts (tenant_id, source_id, id) ON DELETE RESTRICT;
CREATE INDEX ix_index_attempt_source_run ON index_attempts (tenant_id, source_sync_attempt_id, status)
    WHERE source_sync_attempt_id IS NOT NULL;
CREATE INDEX ix_source_run_page ON source_sync_attempts (tenant_id, source_id, created_at DESC, id DESC);
CREATE INDEX ix_source_run_completed ON source_sync_attempts (tenant_id, source_id, run_completed_at DESC, id DESC)
    WHERE run_completed_at IS NOT NULL;
CREATE INDEX ix_source_run_retention ON source_sync_attempts (run_completed_at, id)
    WHERE run_completed_at IS NOT NULL;
CREATE INDEX ix_source_run_current ON source_sync_attempts (tenant_id, source_id, created_at DESC, id DESC)
    WHERE (history_version = 1 AND run_completed_at IS NULL) OR status IN ('NOT_STARTED','IN_PROGRESS');
CREATE INDEX ix_source_run_successful ON source_sync_attempts (tenant_id, source_id, run_completed_at DESC, id DESC)
    WHERE run_completed_at IS NOT NULL AND status = 'SUCCEEDED'
        AND indexing_failed = 0 AND indexing_cancelled = 0 AND indexing_superseded = 0;

CREATE TABLE source_run_files (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    file_id VARCHAR(256) NOT NULL,
    file_name VARCHAR(255),
    outcome VARCHAR(16) CHECK (outcome IN ('ACQUIRED', 'UNCHANGED', 'FAILED', 'SKIPPED')),
    PRIMARY KEY (tenant_id, run_id, file_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES source_sync_attempts (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE source_run_errors (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    error_key VARCHAR(300) NOT NULL,
    operation_id UUID,
    item_id UUID,
    file_id VARCHAR(256),
    file_name VARCHAR(255),
    stage VARCHAR(24) NOT NULL CHECK (stage IN ('PROVIDER', 'STORAGE_READ', 'STORAGE_WRITE', 'EXTRACTION', 'PUBLICATION', 'SYSTEM')),
    code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, run_id, error_key),
    FOREIGN KEY (tenant_id, run_id) REFERENCES source_sync_attempts (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_source_run_error_page ON source_run_errors (tenant_id, run_id, occurred_at DESC, id DESC);

CREATE FUNCTION source_run_error_stage(code TEXT) RETURNS TEXT
LANGUAGE SQL IMMUTABLE AS $$
    SELECT CASE
        WHEN code LIKE 'SOURCE_STORAGE_READ_%' THEN 'STORAGE_READ'
        WHEN code LIKE 'SOURCE_STORAGE_WRITE_%' THEN 'STORAGE_WRITE'
        WHEN code LIKE 'SOURCE_EXTRACTION_%' THEN 'EXTRACTION'
        WHEN code LIKE 'SOURCE_PUBLICATION_%' THEN 'PUBLICATION'
        WHEN code LIKE 'SOURCE_GOOGLE_%' THEN 'PROVIDER'
        ELSE 'SYSTEM' END
$$;

-- Acquisition completion is independent of child indexing; this timestamp closes only the owned run.
CREATE FUNCTION source_run_finalize() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.history_version = 1 AND NEW.status NOT IN ('NOT_STARTED', 'IN_PROGRESS')
        AND NEW.indexing_pending = 0 AND NEW.run_completed_at IS NULL THEN
        NEW.run_completed_at := CURRENT_TIMESTAMP;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER source_run_finalize BEFORE UPDATE ON source_sync_attempts
    FOR EACH ROW EXECUTE FUNCTION source_run_finalize();

CREATE FUNCTION source_run_acquisition_error() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.history_version = 1 AND NEW.status = 'FAILED' AND OLD.status IN ('NOT_STARTED', 'IN_PROGRESS')
        AND NEW.error_code IS NOT NULL THEN
        INSERT INTO source_run_errors (id, tenant_id, run_id, error_key, operation_id, stage, code)
        VALUES (gen_random_uuid(), NEW.tenant_id, NEW.id, 'RUN', NEW.id,
            source_run_error_stage(NEW.error_code), NEW.error_code)
        ON CONFLICT (tenant_id, run_id, error_key) DO NOTHING;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER source_run_acquisition_error AFTER UPDATE OF status ON source_sync_attempts
    FOR EACH ROW EXECUTE FUNCTION source_run_acquisition_error();

-- All status writers, including bulk authority cancellation, participate in the same transaction.
CREATE FUNCTION source_run_index_transition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
                file_id, file_name, stage, code)
            SELECT gen_random_uuid(), NEW.tenant_id, NEW.source_sync_attempt_id, 'INDEX:' || NEW.id,
                NEW.id, NEW.connector_item_id, v.provider_file_id, o.filename,
                source_run_error_stage(COALESCE(NEW.error_code, 'SOURCE_EXTRACTION_INTERNAL')),
                COALESCE(NEW.error_code, 'SOURCE_EXTRACTION_INTERNAL')
            FROM connector_item_versions v JOIN stored_objects o ON o.tenant_id = v.tenant_id AND o.id = v.stored_object_id
            WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.connector_item_version_id
            ON CONFLICT (tenant_id, run_id, error_key) DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER source_run_index_transition AFTER INSERT OR UPDATE OF status, source_sync_attempt_id ON index_attempts
    FOR EACH ROW EXECUTE FUNCTION source_run_index_transition();
