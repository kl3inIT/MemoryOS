ALTER TABLE chat_web_connection DROP CONSTRAINT chat_web_connection_provider_check;
ALTER TABLE chat_web_connection ADD CONSTRAINT chat_web_connection_provider_check
    CHECK (provider IN ('BRAVE','TAVILY','EXA','SERPER','GOOGLE_PSE','SEARXNG','NINEROUTER','FIRECRAWL'));
