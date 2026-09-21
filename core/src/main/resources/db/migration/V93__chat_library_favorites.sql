-- MEM-152 phase 2: the owner can star a file to keep it at hand. Renaming needs no column: it rewrites the
-- file's own name, which every listing, search and download already reads.
ALTER TABLE chat_user_file ADD COLUMN favorite_at TIMESTAMPTZ;
ALTER TABLE chat_file_artifact ADD COLUMN favorite_at TIMESTAMPTZ;
ALTER TABLE chat_image_artifact ADD COLUMN favorite_at TIMESTAMPTZ;
