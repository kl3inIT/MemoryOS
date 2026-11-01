-- MEM-93: access-only index updates rewrite a document's access fields in place, without re-embedding
-- or hiding the document from Search while the update runs.
-- NOT VALID avoids scanning the table under this migration's exclusive lock; existing rows already satisfy
-- the previous INDEX/DELETE check. V52 validates the constraint and backfills in a separate transaction.
ALTER TABLE search_index_operations DROP CONSTRAINT search_index_operations_action_check;
ALTER TABLE search_index_operations
    ADD CONSTRAINT search_index_operations_action_check CHECK (action IN ('INDEX', 'DELETE', 'ACCESS')) NOT VALID;
