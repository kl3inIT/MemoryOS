ALTER TABLE stored_objects ADD COLUMN input_kind VARCHAR(32) NOT NULL DEFAULT 'BINARY';
ALTER TABLE stored_objects DROP CONSTRAINT ck_stored_objects_size;
ALTER TABLE stored_objects ADD CONSTRAINT ck_stored_objects_size CHECK (
    (input_kind = 'BINARY' AND size_bytes BETWEEN 1 AND 10485760)
    OR (input_kind = 'NATIVE_SNAPSHOT' AND size_bytes BETWEEN 1 AND 33554432)
);
ALTER TABLE stored_objects ADD CONSTRAINT uq_stored_objects_input_kind UNIQUE (tenant_id, id, input_kind);

-- Browser upload rows cannot authorize or adopt the larger native representation.
ALTER TABLE object_uploads ADD COLUMN input_kind VARCHAR(32) NOT NULL DEFAULT 'BINARY';
ALTER TABLE object_uploads ADD CONSTRAINT ck_object_uploads_input_kind CHECK (input_kind = 'BINARY');
ALTER TABLE object_uploads DROP CONSTRAINT fk_object_uploads_stored_object;
ALTER TABLE object_uploads ADD CONSTRAINT fk_object_uploads_stored_object
    FOREIGN KEY (tenant_id, stored_object_id, input_kind)
    REFERENCES stored_objects (tenant_id, id, input_kind) ON DELETE RESTRICT;

CREATE TABLE object_writes (
    tenant_id UUID NOT NULL,
    stored_object_id UUID NOT NULL,
    write_token UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    write_complete BOOLEAN NOT NULL DEFAULT FALSE,
    write_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
    adoption_deadline TIMESTAMP WITH TIME ZONE,
    cleanup_token UUID,
    cleanup_lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_object_writes PRIMARY KEY (tenant_id, stored_object_id),
    CONSTRAINT fk_object_writes_stored_object FOREIGN KEY (tenant_id, stored_object_id)
        REFERENCES stored_objects (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_object_writes_status CHECK (status IN ('WRITING', 'READY', 'ADOPTED', 'DISCARDED', 'CLEANING')),
    CONSTRAINT ck_object_writes_verified CHECK (
        status NOT IN ('READY', 'ADOPTED') OR (write_complete AND adoption_deadline IS NOT NULL)
    ),
    CONSTRAINT ck_object_writes_cleanup CHECK (
        (status = 'CLEANING' AND cleanup_token IS NOT NULL AND cleanup_lease_until IS NOT NULL)
        OR (status <> 'CLEANING' AND cleanup_token IS NULL AND cleanup_lease_until IS NULL)
    )
);

CREATE INDEX ix_object_writes_expiry ON object_writes (status, write_deadline, adoption_deadline);
CREATE INDEX ix_object_writes_cleanup ON object_writes (cleanup_lease_until, created_at) WHERE status = 'CLEANING';
