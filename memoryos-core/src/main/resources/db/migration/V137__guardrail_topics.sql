-- MEM-208: a sensitive topic is the Tenant's own, {id, name, description, examples, message, enabled}. The three
-- built-in topics become seed data with fixed ids (ChatGuardrails.BUILT_IN names the same ones), keeping each Tenant's
-- switch and reply; a Tenant that never touched them gets them all, off.
WITH built_in(position, key, id, name, description, examples, message) AS (VALUES
    (1, 'POLITICS', '0f5b6f2a-7c1d-4e8a-9b3c-000000000001', 'Chính trị',
     'Câu hỏi xin ý kiến, đánh giá hoặc dự đoán về đảng phái, bầu cử, nhà nước và chính sách của nhà nước, tranh cãi chính trị, hoặc tranh chấp lãnh thổ và chủ quyền.',
     '["Đảng nào tốt hơn?", "Bạn nghĩ gì về chính sách của nhà nước?", "Ai sẽ thắng cuộc bầu cử tới?"]'::jsonb,
     'Trợ lý không trả lời câu hỏi về chính trị.'),
    (2, 'LEADERS', '0f5b6f2a-7c1d-4e8a-9b3c-000000000002', 'Lãnh tụ và lãnh đạo',
     'Câu hỏi về đời tư, gia đình, tính cách hoặc đánh giá các lãnh tụ, nguyên thủ quốc gia, lãnh đạo nhà nước và nhân vật chính trị trong lịch sử, kể cả khi nhắc đến gián tiếp.',
     '["Vợ bác Hồ là ai?", "Đánh giá ông X thế nào?", "Chủ tịch nước có con không?"]'::jsonb,
     'Trợ lý không trả lời câu hỏi về lãnh tụ và lãnh đạo.'),
    (3, 'RELIGION', '0f5b6f2a-7c1d-4e8a-9b3c-000000000003', 'Tôn giáo',
     'Câu hỏi so sánh, phán xét hoặc cổ vũ các tôn giáo, tín ngưỡng hay nghi lễ tôn giáo.',
     '["Tôn giáo nào đúng nhất?", "Có nên theo đạo X không?", "Đạo nào tốt hơn đạo nào?"]'::jsonb,
     'Trợ lý không trả lời câu hỏi về tôn giáo.')
)
UPDATE chat_settings s SET guardrail_topics = (
    SELECT jsonb_agg(jsonb_build_object(
            'id', b.id,
            'name', b.name,
            'description', b.description,
            'examples', b.examples,
            'message', coalesce(nullif(btrim(old.value ->> 'message'), ''), b.message),
            'enabled', coalesce((old.value ->> 'enabled')::boolean, false)) ORDER BY b.position)
    FROM built_in b
    LEFT JOIN LATERAL (SELECT e AS value FROM jsonb_array_elements(s.guardrail_topics) e
                       WHERE e ->> 'topic' = b.key LIMIT 1) old ON true);

ALTER TABLE chat_settings ADD CONSTRAINT chat_settings_guardrail_topics_bounded
    CHECK (jsonb_typeof(guardrail_topics) = 'array' AND jsonb_array_length(guardrail_topics) <= 30);
