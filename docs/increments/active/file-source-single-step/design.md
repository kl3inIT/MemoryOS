# Single-step FILE source setup

Replace create-empty-source then upload with one form: source name, dropzone, selected file and Upload and create. Preserve existing backend contracts, single-file selection and 10 MiB limit. No backend, ACL or deployment changes.

Reference: `.tmp/onyx`, e3320d8fe. `connectors.tsx` configures FILE with file_locations and no advanced values; `AddConnectorPage.tsx` uses submitFiles and noAdvanced; `FileUpload.tsx` provides drop/browse and selected-file names. Adopt the unified form, not unsupported access controls or Onyx's upload API.

Submit orchestrates source creation, checksum, authorization, direct PUT and finalization. Retain created source ID for retry. Persist pending finalization in existing cross-route recovery context before finalizing, never repeating PUT during finalization retry. Abort on unmount. Navigate to detail after success for asynchronous indexing. Remove unused wizard shell. Visual approval before commit/push.
