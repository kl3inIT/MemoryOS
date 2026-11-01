# Implementation plan

Design: [design.md](design.md).

## 1. Policy in the registry

- [x] V166: `policy` and `policy_customized` from `enabled`, `approval_required` and the annotations; the old
  columns dropped.
- [x] `McpToolPolicy`, `McpToolRisk`; defaults and the destructive limit in `McpServerRules`.
- [x] Refresh sets defaults on tools that are not customized and keeps the rest.
- [x] `setToolPolicy`, `setGroupPolicy`, `resetToolPolicies`, audited; the three old setters removed.
- [x] `McpTurnService` and `McpTurnTools` read the policy.

## 2. Tools listed on connect

- [x] `discoverTools`: list with the caller's credential when the snapshot is empty; never throws.
- [x] Called after the OAuth callback connects and after a server is created or saved.

## 3. No silent cut

- [x] Tools left out over 64 are counted per server and named in the model's notice.
- [x] Offered tool count and definition characters recorded per turn.

## 4. API and web

- [x] Policy endpoints and `McpToolResponse.policy`, `policyCustomized`, `risk`; old endpoints removed; OpenAPI and
  the web client regenerated.
- [x] Tool list: three groups, group selector, destructive without "Luôn cho phép", customized mark, "Về mặc
  định".
- [x] "Làm mới công cụ" in the menu; one spinner in a pending button.

## 5. Verification

- [x] Core tests: defaults, refresh keeping customized policies, group and reset, destructive limit, discovery,
  the cut notice.
- [x] Web tests for the tool list; e2e fixture updated.
- [x] Staging: Linear's write tools ask without manual changes; a fresh connection lists tools.
- [x] Spec, tests matrix and runbook updated.

Evidence: [verification.md](verification.md).
