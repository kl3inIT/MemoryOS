# 19. Provider families use one adapter per provider behind a registry

Date: 2026-09-30

## Status

Accepted; implemented 2026-09-30 in [provider adapter registries](../increments/completed/provider-adapter-registries/design.md)
(PRs #401 and #402). Narrows the single-implementation rule in [ADR 0015](0015-capability-module-map.md#layout-inside-a-module)
for provider families only; the rest of ADR 0015 stands. Consistent with
[ADR 0002](0002-no-speculative-operational-surfaces.md): a registry with real adapters and a real selection is not
speculative.

## Context

Several capabilities let an administrator or the deployment choose among interchangeable external protocols: Web
search and reading (eight providers), speech (five), image generation (two), chat models and connectors. Each chose
its provider with `switch` statements on an enum, spread across services, and the enums carried flags and catalogs
(required key or endpoint, suggested models, voices, sizes). Adding or changing a provider meant editing every
`switch` of its family and a shared enum. The conventions discouraged the alternative: they banned "generic
registries" and interfaces without a current caller, and ADR 0015 collapses an internal interface with one
implementation.

## Decision

A provider family is built, from its first provider, as one adapter class per provider behind one interface per
function, collected into a registry keyed by the persisted provider identity.

- **Closed families** (the product supports a fixed set: Web, Voice, Image, Sources) key the registry by an enum in
  an `EnumMap`. The registry holds exactly one adapter per constant and fails at startup on a missing or duplicate
  one, replacing the compiler's `switch` exhaustiveness.
- **Open families** (a new protocol is a new bean and nothing else changes: `ai.ProviderAdapter`, held to that by
  `secondRegisteredAdapterNeedsNoExecutorChangesOrDummyCredentials`) key it by the adapter's stable string type and
  fail at startup only on a duplicate.
- A function only some providers offer is its own interface extending the family's base; the registry answers it by
  the interfaces an adapter implements. No adapter has unsupported methods, and none branches on its own provider.
- The enum is identity only. What a provider needs, suggests and offers is a `<Family>ProviderCapabilities` record
  returned by its adapter.
- Transport, parsing, bounds and the provider-call observation stay in one client per family that delegates.
- An internal interface with one provider is kept in a provider family. This is the exception to ADR 0015.
- Still not built without a requirement: a registry framework shared across families, and registration of providers
  at run time.

Adapters live in the capability's `adapter` subpackage, or next to the provider code they wrap when that code is
package-private by module design (Voice) or owns persistence while `connector.adapter` belongs to `sources`
(connectors). Names follow the role: `<Function>Adapter`, `<Vendor><Function>Adapter` or `<Vendor><Family>Adapter`,
`<Family>AdapterRegistry`, `<Family>ProviderCapabilities`.

## Consequences

- A provider's protocol is one file; adding one adds a class and, for a closed family, an enum constant and its
  database CHECK. No dispatch site changes.
- A missing adapter stops the application at startup instead of failing a request.
- More files per family, and a startup check in place of compile-time exhaustiveness; each registry has a test for
  completeness and duplicates.
- Connectors move to this shape in [MEM-118](../increments/active/mem-118-connector-adapters-lark/design.md).
