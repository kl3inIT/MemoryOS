CREATE TABLE actors (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE external_identity_bindings (
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    actor_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_external_identity_bindings PRIMARY KEY (issuer, subject),
    CONSTRAINT fk_external_identity_bindings_actor
        FOREIGN KEY (actor_id) REFERENCES actors (id) ON DELETE RESTRICT
);

CREATE INDEX ix_external_identity_bindings_actor_id
    ON external_identity_bindings (actor_id);
