CREATE TABLE chat_web_connection (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    provider VARCHAR(32) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL DEFAULT '',
    engine_id VARCHAR(200) NOT NULL DEFAULT '',
    credential TEXT,
    search_active BOOLEAN NOT NULL DEFAULT FALSE,
    content_active BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, provider),
    CHECK (provider IN ('BRAVE','TAVILY','EXA','SERPER','GOOGLE_PSE','SEARXNG','FIRECRAWL')),
    CHECK (NOT search_active OR provider <> 'FIRECRAWL'),
    CHECK (NOT content_active OR provider IN ('TAVILY','EXA','FIRECRAWL'))
);
CREATE UNIQUE INDEX chat_web_search_default ON chat_web_connection(tenant_id) WHERE search_active;
CREATE UNIQUE INDEX chat_web_content_default ON chat_web_connection(tenant_id) WHERE content_active;
ALTER TABLE chat_command ADD COLUMN web_search VARCHAR(16) NOT NULL DEFAULT 'off'
    CHECK (web_search IN ('off','auto','required'));
