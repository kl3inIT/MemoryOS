-- MEM-211 follow-up: llm_provider.builtin_key named the provider seeded from the deployment's own key. V140 removed
-- those providers and the release with V140 stopped mapping the column, so nothing reads or writes it; it was kept only
-- while a rollback to the release before V140 was still possible. Dropping it drops UNIQUE (tenant_id, builtin_key).
-- persona.builtin_key is a different column and stays.
ALTER TABLE llm_provider DROP COLUMN builtin_key;
