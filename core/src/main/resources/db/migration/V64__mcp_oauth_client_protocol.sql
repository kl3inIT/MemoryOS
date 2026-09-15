-- MEM-112 Phase 2b: client authentication and RFC 9207 issuer-parameter requirement per OAuth client.

ALTER TABLE mcp_oauth_client
    ADD COLUMN token_endpoint_auth_method VARCHAR(24) NOT NULL DEFAULT 'client_secret_basic'
        CHECK (token_endpoint_auth_method IN ('none', 'client_secret_basic', 'client_secret_post')),
    ADD COLUMN iss_parameter_required BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE mcp_oauth_client ALTER COLUMN token_endpoint_auth_method DROP DEFAULT;

ALTER TABLE mcp_oauth_client ADD CONSTRAINT ck_mcp_oauth_client_secret_method
    CHECK ((token_endpoint_auth_method = 'none') = (client_secret IS NULL));
