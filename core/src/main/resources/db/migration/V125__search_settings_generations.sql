-- MEM-135: the embedding model is a configuration generation an administrator chooses, not deployment
-- configuration. Each generation owns one OpenSearch index; the application seeds the first PRESENT
-- generation from deployment configuration with the index name already in use, because only the
-- running application can compute that name.

-- An OpenAI-compatible /v1/embeddings endpoint. Its own table, not llm_provider: a Chat provider test
-- sends a chat request that an embedding-only server fails. The credential is the chat catalog's
-- AES-GCM ciphertext bound to the Tenant and provider, or 'deployment' for the deployment's own key.
CREATE TABLE embedding_provider (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    endpoint VARCHAR(2048) NOT NULL CHECK (endpoint ~ '^https?://'),
    credential TEXT,
    data_boundary VARCHAR(16) NOT NULL DEFAULT 'EXTERNAL' CHECK (data_boundary IN ('INTERNAL', 'EXTERNAL')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name)
);

-- One generation is one index. What decides the vectors (model, dimensions, document prefix and chunk
-- convention) is recorded here and in the index's _meta; the endpoint belongs to the provider and can
-- change without a new index.
CREATE TABLE search_settings (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider_id UUID NOT NULL,
    model VARCHAR(200) NOT NULL CHECK (length(trim(model)) > 0),
    dimensions INTEGER NOT NULL CHECK (dimensions BETWEEN 1 AND 16000),
    query_prefix VARCHAR(1000) NOT NULL DEFAULT '',
    document_prefix VARCHAR(1000) NOT NULL DEFAULT '',
    minimum_semantic_score DOUBLE PRECISION NOT NULL CHECK (minimum_semantic_score BETWEEN 0 AND 1),
    chunk_convention VARCHAR(80) NOT NULL,
    index_identity VARCHAR(160) NOT NULL UNIQUE CHECK (index_identity ~ '^[a-z][a-z0-9-]{0,159}$'),
    status VARCHAR(8) NOT NULL CHECK (status IN ('PRESENT', 'FUTURE', 'PAST')),
    automatic BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMPTZ,
    retained_until TIMESTAMPTZ,
    CHECK (status <> 'PRESENT' OR activated_at IS NOT NULL),
    CHECK ((status = 'PAST') = (retained_until IS NOT NULL)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, provider_id) REFERENCES embedding_provider(tenant_id, id) ON DELETE RESTRICT
);
-- Exactly one PRESENT (seeding supplies the first) and at most one FUTURE per Tenant.
CREATE UNIQUE INDEX search_settings_one_present ON search_settings(tenant_id) WHERE status = 'PRESENT';
CREATE UNIQUE INDEX search_settings_one_future ON search_settings(tenant_id) WHERE status = 'FUTURE';

-- Which content generation of a document is complete in which index. A document can be ready in the
-- PRESENT index while it is being rebuilt in a FUTURE one. No foreign key to search_settings: the
-- rows backfilled here name the index in use before its generation is seeded.
CREATE TABLE document_search_projection (
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    index_identity VARCHAR(160) NOT NULL,
    generation UUID NOT NULL,
    ready_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, document_id, index_identity),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX document_search_projection_index ON document_search_projection(index_identity, tenant_id);

INSERT INTO document_search_projection(tenant_id, document_id, index_identity, generation)
SELECT tenant_id, id, search_index_identity, searchable_generation FROM documents
WHERE searchable_generation IS NOT NULL AND search_index_identity IS NOT NULL;
