# FILE indexing quick fix

Scope: fix the observed Redis idle-read timeout and native file input vertical alignment.
Leave concurrent MinIO changes untouched. No changes to business authority or upload APIs.

The Redis command timeout must leave headroom above the 2-second blocking stream read.
Raise its default to 5 seconds. Log exception class and root-cause class without logging
potentially sensitive exception messages. Add an idle-stream regression using production
BLOCK duration. Align the FILE selector using the existing control height and design tokens.

Integrate the fix directly into main as requested. Use the normal image/deployment path;
do not ship a derived hotfix image or Compose override. No API/database migration.
