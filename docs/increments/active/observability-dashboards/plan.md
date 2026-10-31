# Observability dashboards — plan

- [x] Inventory the dashboard, alerts and the metric names staging Prometheus holds.
- [x] Review references (Grafana 20352/18812, Spring AI observability, Tempo generator, dashboard best practices).
- [x] Turn on telemetry export for the production profile and label each environment (`MEMORYOS_ENVIRONMENT`).
- [x] Add `memoryos.chat.turn`, `memoryos.chat.turn.first.text` and `memoryos.chat.guardrail.check`, with tests.
- [x] Publish histogram buckets for the model, Chat, search and queue-wait timers.
- [x] Enable the Tempo metrics-generator and the Prometheus remote-write receiver.
- [x] Provision the Service, Chat & AI and Traces dashboards and link them from the Overview.
- [x] Add model-error and failed-turn alerts.
- [x] Update the observability guideline and runbook.
- [x] Check every dashboard query against staging Prometheus: all parse; 35 return nothing until the new metrics, span metrics or traffic exist.
- [x] After merge: staging runs the stack from `releases/97663fcc…` (2026-09-27); all five services ready, the four
  dashboards provisioned, Tempo writing span metrics (14 series) and the service graph (3).
- [ ] After the next production promotion: confirm production series and traces arrive, then update its observability stack the same way.

## Staging acceptance (2026-10-19)

Production confirmed: its stack (revision `6ac59f77`) receives API and worker series and traces labelled production, with the MCP endpoint row. Staging's stack still runs `46540b18` without that row, and Tempo there is down: it is killed at its 768 MiB limit while compacting old blocks on start.

Staging rollout (2026-10-19): the stack now runs from `/apps/memoryos/observability-stack` at `5dadbc7d`, as production does, with Grafana's volume backed up first. Its containers had been created from `97663fcc` and `46540b18`, so Tempo still carried the 768 MiB limit that `e1365574` removed and was killed compacting 2.4 GB of blocks on every start. Tempo 3.1.0 now runs without a limit at about 780 MiB, with no restart; Prometheus loads the ten rules; `traces_spanmetrics_calls_total` has 24 series and the Chat AI dashboard has its MCP rows. Production still runs `6ac59f77` and waits for its next deployment.
