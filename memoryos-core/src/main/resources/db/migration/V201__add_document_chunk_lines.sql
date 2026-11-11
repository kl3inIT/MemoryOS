-- The text lines the OCR located in the blocks a passage cites, beside its provenance rather than in it, so a
-- citation stays within Chat's provenance bound. Search's reader asks for them to mark the line holding a question.
ALTER TABLE document_chunks ADD COLUMN lines_json TEXT;
