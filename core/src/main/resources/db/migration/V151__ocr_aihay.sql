-- MEM-243: AI Hay's OCR, a cloud service that reads one page picture a call.
ALTER TABLE ocr_connection DROP CONSTRAINT ocr_connection_provider_check;
ALTER TABLE ocr_connection ADD CONSTRAINT ocr_connection_provider_check
    CHECK (provider IN ('PADDLEOCR_VL', 'MODEL', 'AIHAY'));
