ALTER TABLE stored_objects DROP CONSTRAINT ck_stored_objects_size;
ALTER TABLE stored_objects ADD CONSTRAINT ck_stored_objects_size CHECK (
    (input_kind = 'BINARY' AND size_bytes BETWEEN 1 AND 104857600)
    OR (input_kind = 'NATIVE_SNAPSHOT' AND size_bytes BETWEEN 1 AND 33554432)
);

ALTER TABLE connector_item_versions DROP CONSTRAINT ck_item_versions_input;
ALTER TABLE connector_item_versions ADD CONSTRAINT ck_item_versions_input CHECK (
    input_format IN ('BINARY', 'GOOGLE_SHEETS', 'GOOGLE_DOCS')
    AND size_bytes BETWEEN 1 AND CASE WHEN input_format = 'BINARY' THEN 104857600 ELSE 33554432 END
    AND ((provider_file_id IS NULL AND input_format = 'BINARY' AND scope_revision IS NULL AND credential_revision IS NULL)
      OR (provider_file_id IS NOT NULL AND scope_revision > 0 AND credential_revision > 0))
);
