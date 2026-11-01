CREATE TABLE jit_allowed_provider (
    alias VARCHAR(128) PRIMARY KEY,
    created_by UUID REFERENCES actors(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
