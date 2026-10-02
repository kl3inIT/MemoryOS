# 20. Chat models come only from the catalog

Date: 2026-10-02

## Status

Accepted; implemented in [no deployment Chat model](../increments/active/no-deployment-chat-model/design.md)
([MEM-211](https://linear.app/memory-os/issue/MEM-211)).

Supersedes the `memoryos.chat.provider.*` configuration keys that
[ADR 0015](0015-capability-module-map.md) listed as unchanged contracts. The rest of ADR 0015 stands.

## Context

Every Tenant was bootstrapped with an "OpenAI" provider and made its model the Chat default.

- The provider's credential was a marker that resolved to a server secret, `MEMORYOS_CHAT_API_KEY_FILE`, which an administrator could neither see nor replace.
- When that key expired on staging (2026-10-01), every model of the provider failed with 401. The Models page could not fix it, and work kept being routed to those models: the meeting minutes failed on 2026-10-02.

Onyx keeps its environment-seeded provider (`GEN_AI_API_KEY`) for development only. A production install starts with no provider, and an administrator adds one.

## Decision

- **Every Chat provider and its key is a catalog entry an administrator adds.**
  - Provisioning a Tenant creates the unset Chat default and the task rows, and no provider or model.
  - The administrator-chosen Chat default stays.
- **No model key or model comes from deployment configuration.**
  - The `memoryos.chat.provider.*` keys, the `deployment` credential marker and the deployment's own OpenAI clients are removed.
  - There is no development-only seeding mode, following [ADR 0002](0002-no-speculative-operational-surfaces.md).
- **Embabel starts with no model through its own placeholder.**
  - The placeholder is `setup-required`, from `embabel-agent-byok-autoconfigure`.
  - Every MemoryOS call names its catalog service.
- **The provider each Tenant was seeded with is removed in every environment**, together with its models: migration V140, owner decision 2026-10-02.
  - What named those models is unset, as deleting them in the catalog would.
  - Per [ADR 0018](0018-schema-changes-preserve-data.md), this is what is lost on purpose: a provider whose key nothing reads any more.

## Consequences

- **A new environment answers no chat** until an administrator adds a provider and picks the Chat default.
- **An environment whose Chat default was a seeded model** has no default after the deploy, until an administrator picks one.
- **The secret the seeded provider read is no longer used by Chat.** The `model_api_key` file still serves embedding.
- **Rollback:** `llm_provider.builtin_key` stays until no release maps it, so the previous release still runs on the new schema, but the removed providers do not come back.
