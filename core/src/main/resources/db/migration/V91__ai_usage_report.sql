-- MEM-139: generated usage reports (Onyx usage_reports). A manager requests one for a UTC day range; the Worker
-- claims it under a lease, stores the ZIP through object storage and marks it ready or failed.
CREATE TABLE ai_usage_report (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    requested_by UUID NOT NULL REFERENCES actors(id),
    period_from DATE NOT NULL,
    period_to DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED')),
    attempts INT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_until TIMESTAMPTZ,
    stored_object_id UUID,
    object_key VARCHAR(240),
    size_bytes BIGINT CHECK (size_bytes >= 0),
    -- Whether the ZIP carries the PDF; a render failure still ships the CSV files, as in Onyx.
    has_pdf BOOLEAN NOT NULL DEFAULT FALSE,
    failure VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT fk_ai_usage_report_object FOREIGN KEY (tenant_id, stored_object_id)
        REFERENCES stored_objects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_ai_usage_report_period CHECK (period_from <= period_to AND period_to - period_from < 366),
    CONSTRAINT ck_ai_usage_report_ready CHECK (status <> 'READY'
        OR (stored_object_id IS NOT NULL AND object_key IS NOT NULL AND size_bytes IS NOT NULL AND finished_at IS NOT NULL)),
    CONSTRAINT ck_ai_usage_report_failed CHECK (status <> 'FAILED' OR (failure IS NOT NULL AND finished_at IS NOT NULL)),
    CONSTRAINT ck_ai_usage_report_running CHECK (status <> 'RUNNING' OR lease_until IS NOT NULL)
);
CREATE INDEX ix_ai_usage_report_tenant_created ON ai_usage_report (tenant_id, created_at DESC);
CREATE INDEX ix_ai_usage_report_open ON ai_usage_report (created_at) WHERE status IN ('PENDING', 'RUNNING');
