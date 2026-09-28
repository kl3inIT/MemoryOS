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
- [ ] After merge: pull the configuration on staging, restart the observability stack, and check every panel renders.
- [ ] After the next production promotion: confirm production series and traces arrive, then update its observability stack the same way.
