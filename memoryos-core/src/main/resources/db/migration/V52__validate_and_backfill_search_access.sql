-- MEM-93: validation takes a lock that still allows writes, unlike V51's constraint replacement.
ALTER TABLE search_index_operations VALIDATE CONSTRAINT search_index_operations_action_check;

-- Backfill: every currently searchable document receives its access fields once.
INSERT INTO search_index_operations (id, tenant_id, document_id, generation, action, index_identity)
SELECT gen_random_uuid(), document.tenant_id, document.id, document.content_generation, 'ACCESS', document.search_index_identity
FROM documents document
JOIN tenants tenant ON tenant.id = document.tenant_id AND tenant.status = 'ACTIVE'
WHERE document.status = 'ELIGIBLE'
  AND document.searchable_generation = document.content_generation
  AND document.search_index_identity IS NOT NULL
ON CONFLICT (tenant_id, document_id, generation, action, index_identity) DO NOTHING;
