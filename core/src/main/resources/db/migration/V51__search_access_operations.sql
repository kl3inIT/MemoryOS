-- MEM-93: access-only index updates rewrite a document's access fields in place, without re-embedding
-- or hiding the document from Search while the update runs.
ALTER TABLE search_index_operations DROP CONSTRAINT search_index_operations_action_check;
ALTER TABLE search_index_operations
    ADD CONSTRAINT search_index_operations_action_check CHECK (action IN ('INDEX', 'DELETE', 'ACCESS'));

-- Backfill: every currently searchable document receives its access fields once.
INSERT INTO search_index_operations (id, tenant_id, document_id, generation, action, index_identity)
SELECT gen_random_uuid(), document.tenant_id, document.id, document.content_generation, 'ACCESS', document.search_index_identity
FROM documents document
JOIN tenants tenant ON tenant.id = document.tenant_id AND tenant.status = 'ACTIVE'
WHERE document.status = 'ELIGIBLE'
  AND document.searchable_generation = document.content_generation
  AND document.search_index_identity IS NOT NULL
ON CONFLICT (tenant_id, document_id, generation, action, index_identity) DO NOTHING;
