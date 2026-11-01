-- MEM-273: a web conversation nobody asked anything in is not a topic. The earlier sidebar created one on every
-- click on an assistant; lists now leave out any conversation without a question, and the ones older than a day are
-- soft-deleted so the retention purge removes them. A conversation holds no upload of its own, and one without a
-- question has no answer and so no generated file.
UPDATE chat_session s SET deleted_at = CURRENT_TIMESTAMP
WHERE s.deleted_at IS NULL AND NOT s.temporary AND s.surface = 'WEB'
  AND s.created_at < CURRENT_TIMESTAMP - INTERVAL '1 day'
  AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.session_id = s.id AND m.role = 'USER');
