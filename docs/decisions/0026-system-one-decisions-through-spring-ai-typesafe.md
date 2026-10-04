# 26. System One decisions go through the spring-ai-typesafe components

Date: 2026-10-04

## Status

Accepted; implementation started 2026-10-04 in
[System One classification](../increments/active/mem-198-system-one/design.md)
([MEM-198](https://linear.app/memory-os/issue/MEM-198)) with the guardrail check.

## Context

MEM-198 connected System One services and first asked them one hand-built choice question through
`typesafe-java-sdk`, read into types of our own (`SystemOneClient.Question`, `Decision`). The language-model path
of the same check asked for one text label and read it with patterns. Two classifiers gave two request shapes, two
answer types and two ways to a verdict.

The owner set the aim on 2026-10-04: one maintained library and one pattern for everything System One, and one answer
type for the guardrail check whichever classifier runs it. `spring-ai-typesafe` (`org.springaicommunity`) ships the
client and, in `typesafe-spring-ai`, components built on it: `JevGuardrail`, `JevJudge`, document filtering and
reranking, tool search and decision composition. This extends [ADR 0025](0025-outbound-http-through-the-highest-level-client.md),
whose first choice is the library's own client.

## Decision

- **One way in.** Every System One connection, whatever its type, yields a `TypeSafeClient`
  (`SystemOneClients.client(connection)`). A connection type is an adapter that builds that client on
  `shared.OutboundHttp`; nothing else about a type is special. Cloudflare's address and possible `result` envelope
  are handled under its client.
- **The library's vocabulary.** Requests, questions and answers are the SDK's (`SystemOneRequest`, `Noul`, `Choice`,
  `Score`, `SystemOneResponse`). MemoryOS defines no parallel types.
- **The library's component for a use it covers.** The guardrail check is a `JevGuardrail`: each enabled topic is a
  hazard that blocks, with the library's review and action thresholds. A later use (judging an answer, filtering
  passages, choosing a tool) takes the library's component for it on the same client before anything is written
  here.
- **One answer type for a decision with two classifiers.** A language model that runs the check is asked the same
  questions and answers with one flat JSON object of probabilities; MemoryOS reads it into a `SystemOneResponse` and
  `JevGuardrail.evaluate` decides, as it does for a System One answer.
- **MemoryOS keeps** the Tenant's connections (storage, keys, authorization, audit), the transport rules, the mapping
  from Tenant settings to the library's configuration, and what a failure means.

## Consequences

- `typesafe-spring-ai` 0.4.0 is a dependency of `core`; at run time it adds `typesafe-java-sdk` and
  `spring-ai-client-chat`, which `core` already had. The library is young (four releases in weeks, parts marked
  experimental), so the seam is kept thin: adapters build the client, and `JevGuardrail.evaluate` is the only
  library call in a Chat turn.
- The library's advisors (`JevGuardrailAdvisor`, `JevSelfRefineAdvisor`) are not used: they sit in a `ChatClient`
  advisor chain, which a Chat turn on Embabel does not run through. `JevGuardrail` itself needs no `ChatClient`.
- The guardrail's `screen(...)` is not used either: `evaluate(...)` lets the request carry a question of ours (is
  the message conversation), leaves out the library's default severity rubric, which rates harm rather than a
  Tenant's topics, and keeps every connection type on its own transport.
- The check has thresholds (0.35 review, 0.70 block) before the measurement that was to decide whether it needs any.
  A probability between them lets the turn through and is counted as a review.
- The language-model path returns JSON again. A flat object, a missing question read as no, and the object taken
  from whatever surrounds it limit what broke on 2026-10-01; provider-enforced structured output for single model
  calls is a separate change.
- A language model's probabilities are its own estimate, not calibrated; the thresholds may need to differ by
  classifier once measured.
