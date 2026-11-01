-- MEM-246: where a conversation is held. One held in a channel stays out of its owner's sidebar and search.
ALTER TABLE chat_session
    ADD COLUMN surface VARCHAR(16) NOT NULL DEFAULT 'WEB';

ALTER TABLE chat_session
    ADD CONSTRAINT ck_chat_session_surface CHECK (surface IN ('WEB', 'CHANNEL'));
