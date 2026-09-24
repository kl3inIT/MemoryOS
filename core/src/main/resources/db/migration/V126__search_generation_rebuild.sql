-- MEM-135 part 2: a FUTURE generation is rebuilt in the background, switched, restored and cleaned up.

-- A PAST generation whose retention ended loses its index. Repeated failures to delete the index are counted, and
-- after three the generation is marked blocked so the administration page can warn.
ALTER TABLE search_settings
    ADD COLUMN cleanup_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cleanup_attempts >= 0),
    ADD COLUMN cleanup_blocked_at TIMESTAMPTZ;

-- The chunk count written to that index. Rebuilding with a new chunk convention rewrites the shared chunk rows, so
-- the count on documents describes the newest chunks, not the ones an older index holds.
ALTER TABLE document_search_projection ADD COLUMN chunk_count INTEGER CHECK (chunk_count > 0);
UPDATE document_search_projection p SET chunk_count = d.chunk_count
FROM documents d
WHERE d.tenant_id = p.tenant_id AND d.id = p.document_id AND d.chunk_generation = p.generation AND d.chunk_count > 0;

-- Rebuild progress and cancellation read the work of one index.
CREATE INDEX search_index_operations_identity ON search_index_operations(index_identity, status);
