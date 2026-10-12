-- MEM-243: the service a Tenant chose to read scans and images, configured by an administrator instead of the
-- deployment's environment. One connection per type, as the Web, image and voice connections; the active one is the
-- reader, and with none Docling reads scans with its own OCR.
CREATE TABLE ocr_connection (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('PADDLEOCR_VL')),
    endpoint VARCHAR(2048) NOT NULL DEFAULT '',
    credential TEXT,
    data_boundary VARCHAR(16) NOT NULL DEFAULT 'INTERNAL' CHECK (data_boundary IN ('INTERNAL', 'EXTERNAL')),
    active BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, provider)
);
CREATE UNIQUE INDEX ocr_connection_reader ON ocr_connection(tenant_id) WHERE active;
