-- MEM-243: a language model that reads images may be the OCR reader. Its connection names a model configuration
-- instead of a service address; removing the model removes the connection, which returns scans to Docling's own OCR.
ALTER TABLE ocr_connection DROP CONSTRAINT ocr_connection_provider_check;
ALTER TABLE ocr_connection ADD CONSTRAINT ocr_connection_provider_check
    CHECK (provider IN ('PADDLEOCR_VL', 'MODEL'));
ALTER TABLE ocr_connection ADD COLUMN model_configuration_id UUID REFERENCES model_configuration(id) ON DELETE CASCADE;
ALTER TABLE ocr_connection ADD CONSTRAINT ocr_connection_model
    CHECK ((provider = 'MODEL') = (model_configuration_id IS NOT NULL));

-- Pages a model reads are recorded as their own usage flow. Existing rows satisfy the wider check, so NOT VALID
-- avoids scanning the table under this lock; V150 validates.
ALTER TABLE ai_usage DROP CONSTRAINT ai_usage_flow_check;
ALTER TABLE ai_usage ADD CONSTRAINT ai_usage_flow_check
    CHECK (flow IN ('CHAT', 'CHAT_NAMING', 'CHAT_GUARDRAIL', 'DEEP_RESEARCH', 'EMBEDDING_QUERY', 'EMBEDDING_INDEXING',
        'IMAGE_GENERATION', 'IMAGE_EDIT', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'MEETING_MINUTES',
        'MEETING_CORRECTION', 'OCR')) NOT VALID;
