-- MEM-123: AI spending limits (Onyx token_rate_limit). One row per configured limit and Tenant; a PERSON limit is
-- one budget applied to each person separately, not a budget for one named person.
CREATE TABLE ai_usage_limit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('TENANT', 'GROUP', 'PERSON')),
    -- Set only for a Group limit, and removed with the Group it caps.
    group_id UUID,
    -- Tokens and estimated cost answer different questions; a limit may set either or both.
    token_budget BIGINT CHECK (token_budget > 0),
    cost_budget_usd NUMERIC(18, 8) CHECK (cost_budget_usd > 0),
    period_days INTEGER NOT NULL CHECK (period_days BETWEEN 1 AND 366),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_usage_limit_budget CHECK (token_budget IS NOT NULL OR cost_budget_usd IS NOT NULL),
    CONSTRAINT ck_ai_usage_limit_group CHECK ((scope = 'GROUP') = (group_id IS NOT NULL)),
    CONSTRAINT fk_ai_usage_limit_group FOREIGN KEY (tenant_id, group_id)
        REFERENCES iam_groups (tenant_id, id) ON DELETE CASCADE
);

-- One limit per scope, and one per Group: a second limit on the same scope would only ever be shadowed by the
-- smaller of the two, which no one could read off the screen.
CREATE UNIQUE INDEX uq_ai_usage_limit_scope ON ai_usage_limit (tenant_id, scope, group_id) NULLS NOT DISTINCT;

-- The per-person check sums one person's days on every chat turn; ix_ai_usage_tenant_day cannot serve it.
CREATE INDEX ix_ai_usage_tenant_actor_day ON ai_usage (tenant_id, actor_id, day);
