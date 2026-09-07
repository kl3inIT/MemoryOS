ALTER TABLE external_identity_bindings
    ADD CONSTRAINT uq_external_identity_bindings_identity_actor
        UNIQUE (issuer, subject, actor_id);

CREATE TABLE actor_profiles (
    actor_id UUID PRIMARY KEY,
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    display_name TEXT,
    email TEXT,
    email_verified BOOLEAN NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_actor_profiles_exact_binding
        FOREIGN KEY (issuer, subject, actor_id)
        REFERENCES external_identity_bindings (issuer, subject, actor_id)
        ON DELETE CASCADE
);
