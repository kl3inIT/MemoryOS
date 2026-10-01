# The guardrail check as a task model

Status: implemented 2026-10-01; owner approved the change the same day.

## Problem

The guardrail check (MEM-195 Check 1) asks the conversation model for a structured verdict before a grounded or
guarded answer, and fails the turn closed when it cannot read one. On staging (2026-10-01 11:07 UTC) the Tenant's
Chat model, `ocg/deepseek-v4-flash` through 9Router, answered questions well but returned the verdict in the wrong
shape (`InvalidLlmReturnFormatException`), so a turn the model could have answered failed.

## Decision

The check is a task model, `ModelFlow.CHAT_GUARDRAIL`, beside conversation naming and the meeting tasks
([task models](../../../specs/chat-models.md)):

- The administrator picks it on Models › Task models as "Kiểm tra câu hỏi", so a model that returns structured
  output reliably runs the check while the Chat model keeps answering.
- `ChatTurnService` resolves it once per checked turn with `ChatModelSelector.resolveFlow`, which falls back to the
  conversation's model when the task has none or it is no longer usable. A task never fails because of its model.
- V135 adds it for every Tenant with the Tenant's Chat model, as V121 did for every task, so what the page shows is
  what runs. New Tenants get it from `initializeFlows`, which seeds every flow.
- Its usage stays part of the turn's Chat cost (`AiUsageFlow.CHAT`), recorded against the model that ran it.

**Behavior change.** A conversation whose turn uses a model other than the Tenant's Chat model now checks on the
task model, not on the turn's model. That is the point of the task: the check no longer depends on which model
answers.

**Not in scope.** Retrying a malformed verdict and a separate cost category for the check.
