ALTER TABLE index_attempts ADD COLUMN delivery_id UUID;
ALTER TABLE index_attempts ADD COLUMN dispatch_token UUID;
ALTER TABLE index_attempts ADD COLUMN dispatch_lease_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE index_attempts ADD COLUMN redis_message_id VARCHAR(64);
ALTER TABLE index_attempts
    ADD COLUMN next_dispatch_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE index_attempts ADD COLUMN dispatched_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE index_attempts ADD COLUMN dispatch_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE index_attempts ADD COLUMN processing_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE index_attempts ADD COLUMN last_transport_error VARCHAR(64);
ALTER TABLE index_attempts
    ADD CONSTRAINT ck_index_attempts_dispatch_attempts CHECK (dispatch_attempts >= 0);
ALTER TABLE index_attempts
    ADD CONSTRAINT ck_index_attempts_processing_attempts CHECK (processing_attempts >= 0);

ALTER TABLE connector_cleanup_attempts ADD COLUMN delivery_id UUID;
ALTER TABLE connector_cleanup_attempts ADD COLUMN dispatch_token UUID;
ALTER TABLE connector_cleanup_attempts
    ADD COLUMN dispatch_lease_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE connector_cleanup_attempts ADD COLUMN redis_message_id VARCHAR(64);
ALTER TABLE connector_cleanup_attempts
    ADD COLUMN next_dispatch_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE connector_cleanup_attempts ADD COLUMN dispatched_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE connector_cleanup_attempts ADD COLUMN dispatch_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE connector_cleanup_attempts ADD COLUMN processing_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE connector_cleanup_attempts ADD COLUMN last_transport_error VARCHAR(64);
ALTER TABLE connector_cleanup_attempts
    ADD CONSTRAINT ck_cleanup_attempts_dispatch_attempts CHECK (dispatch_attempts >= 0);
ALTER TABLE connector_cleanup_attempts
    ADD CONSTRAINT ck_cleanup_attempts_processing_attempts CHECK (processing_attempts >= 0);

DROP INDEX ix_index_attempts_claim;
DROP INDEX ix_cleanup_attempts_claim;

CREATE INDEX ix_index_attempts_dispatch
    ON index_attempts (status, next_dispatch_at, dispatch_lease_expires_at, created_at);
CREATE INDEX ix_cleanup_attempts_dispatch
    ON connector_cleanup_attempts (status, next_dispatch_at, dispatch_lease_expires_at, created_at);
