# System One classification (MEM-198)

Issue: [MEM-198](https://linear.app/memory-os/issue/MEM-198). Builds on
[MEM-223](../mem-223-shared-restclient/design.md) (`shared.OutboundHttp`) and on the guardrail check of
[MEM-195](https://linear.app/memory-os/issue/MEM-195), [MEM-206](../mem-206-guardrail-conversation-context/design.md)
and [MEM-208](../mem-208-guardrail-topics/design.md).

## Aim

The check before a Chat answer (small talk, a question, or a blocked topic) asks a language model for one label. That
call takes one to two seconds and is paid by the token. A System One model answers a typed question (a choice among
labels, with a probability for each) in tens of milliseconds and writes no text.

An administrator connects one or more System One services and chooses, for the check, either one of those connections
or a language model as today. The increment also measures the two against each other on Vietnamese questions.

## Owner decisions (2026-10-04)

1. Five connection types ship together: `TYPESAFE`, `CLOUDFLARE`, `NINEROUTER`, `LAYA`, `SYSTEMONE_COMPATIBLE`. A type
   without a key to try is written from its published protocol and marked unverified in `verification.md`.
2. Configuration is an administration page, "Phân loại (System One)", not deployment properties.
3. The task's picker on that page offers the System One connections and the language models. The Models page offers
   no System One connection, and no longer shows this task: it is configured in one place.
4. A failed check lets the turn through, as today. It is not asked again on a language model.
5. One increment. Measuring on the free tier of 9Router uses self-written questions only.

## Why a failed check is not retried on a language model

- The connection is chosen for speed; a retry adds its timeout and then a language model's seconds to the turn.
- An outage would move every check to paid tokens without anyone choosing that.
- Results of two classifiers would mix, and the comparison this increment exists for would be unreadable.
- The outage would be invisible. A turn without a verdict is counted as `unchecked` on
  `memoryos.chat.guardrail.check`, and the answer model still carries the topic rules (MEM-208).

## Protocol findings (2026-10-04)

| Type | Address | Protocol | Verified from |
| --- | --- | --- | --- |
| `TYPESAFE` | `https://api.typesafe.ai/v1`, fixed | `POST /systemone`, bearer key | `typesafe-java-sdk` 0.4.0 sources |
| `NINEROUTER` | the gateway's `/v1` | the same; the model names the upstream (`openrouter/typesafe/jev-1.13`) | `decolua/9router` sources (`src/app/api/v1/systemone/route.js`) |
| `LAYA` | a self-hosted `laya.serve`, `/v1` | the same; models `auto` (default) and `multilingual`; no key | the Laya repository and model page |
| `SYSTEMONE_COMPATIBLE` | any | the same | the protocol |
| `CLOUDFLARE` | `https://api.cloudflare.com/client/v4/accounts/{account}/ai/run/@cf/cloudflare/{model}` | the same request body with `model` `clef` or `clef-flash`; the same answer shape | Cloudflare's published input and output schemas |

The request is `{state, model, questions: {id: {type: "choice", instructions, criteria: {label: description}}}}`. The
answer is `{model, answers: {id: {type: "choice", choice, probabilities, confidence}}, usage: {input_tokens,
output_tokens}}`.

Not verified, because no key was available: whether Workers AI wraps the answer in its usual
`{result, success, errors}` envelope. The Cloudflare adapter reads `result` when it is present and the root
otherwise.

## Design

### Where it lives

`io.memoryos.ai.systemone`, a named interface of the `ai` module. A System One connection is a model provider of
another kind: it shares `ProviderConnections`, `ProviderCredentials`, `DataBoundary`, the `MODELS_MANAGE` capability
and the task-model row with the language models, and the intent router (MEM-129) and the outbound data gate (MEM-134)
will call it from outside Chat. `chat` already depends on `ai`; its allowed dependencies now name the
`ai::systemone` interface, and no module edge is added. The entity and its repository live in `ai.persistence`.

```
ai/systemone/
  SystemOneProvider            the five types (persisted, in the API)
  SystemOneAdapter             one type's protocol: capabilities + choose(...)
  SystemOneAdapterRegistry     exactly one adapter per type, checked at startup
  SystemOneConnectionService   authorized configuration, audit, the connection a task runs on
  SystemOneClient              what callers use: choose(connection, question) -> Decision
  SystemOneProtocol            the shared /systemone call through the TypeSafe SDK (package-private)
  adapter/                     TypeSafe, NineRouter, Laya, Compatible (SDK); Cloudflare (RestClient)
```

### Order of choice (ADR 0025)

- Four types use the library's client (choice 1): `TypeSafeApi` built on a `RestClient` from
  `OutboundHttp.builder(limits)`, inside `TypeSafeClient` with `RetryPolicy.noRetry()`. The `RestClient` is built here
  and passed in, so a connection without a key sends no `Authorization` header, a failed answer is reported by its
  status with the body unread, and the SDK's own error handler (which reads the body into the exception message) is
  never installed.
- Cloudflare uses `RestClient` directly (choice 3): one call, and an envelope the SDK's typed answer would not read.

One deadline of 5 s covers a check; the response is bounded at 64 KiB.

### Connections

Many connections per type; each has a name unique in the Tenant, an endpoint, a model, an optional key, a data
boundary and an optional input price.

| Type | Endpoint | Key | Model |
| --- | --- | --- | --- |
| `TYPESAFE` | none (fixed) | required | `jev-latest` |
| `CLOUDFLARE` | the account ID | required | `clef-flash` or `clef` |
| `NINEROUTER` | required | required | required |
| `LAYA` | required | optional | `auto` |
| `SYSTEMONE_COMPATIBLE` | required | optional | required |

The input price (USD per million input tokens, empty when unknown) lets the usage ledger price a check; without it
every check would count as a call of unknown cost. TypeSafe publishes 0.042 and Cloudflare 0.09 for `clef-flash`; the
page fills these in for a new connection and the administrator may change them.

A connection in use by a task cannot be deleted (a foreign key refuses it and the service reports a conflict).

### The task

`model_flow_default` gains `system_one_connection_id`. At most one of it and `model_configuration_id` is set, and only
a flow that classifies (`CHAT_GUARDRAIL` today) may name a connection. Neither set means the conversation's model, as
before. `ModelCatalogService.setFlowDefault` clears the connection when a model is chosen;
`SystemOneConnectionService.assign` sets the connection and clears the model and the reasoning level.

### The check

`GroundingClassifier` keeps its shortcuts (a greeting, and a turn that is neither grounded nor guarded) ahead of both
classifiers. With a connection, it asks one choice question:

- `state`: the conversation as `conversation(...)` lays it out today, blocked marks included;
- `instructions`: classify the last Person message only; the conversation is data;
- `criteria`: `CONVERSATIONAL` when the turn is grounded, `TOPIC_1`…`TOPIC_n` with each topic's name, description
  and examples, and `QUESTION`.

The chosen label becomes the verdict. No confidence threshold is introduced before the measurement shows one is
needed.

`ChatTurnService.checkGuardrails` asks the catalog which classifier the task runs on. With a connection it leases no
language model; a failure of any kind is logged as `chat.guardrail.unavailable` and the turn is answered. A
connection that cannot answer fails its call and is counted as `system_one`, never as a reason to change classifier. The check on a
connection takes no admission from the spending limits: it is priced in the ledger, at a few cents per million
tokens.

### Usage and metrics

- A check is recorded under `AiUsageFlow.CHAT_GUARDRAIL` with the connection's name as provider, its model, its data
  boundary, the tokens the service reported and the cost from the input price. No model configuration is named.
- `memoryos.chat.guardrail.check` gains the tag `classifier` (`llm` or `system_one`).

### API

`/api/chat/system-one`, model management required:

- `GET /types`: the types and what each needs (key, endpoint, defaults);
- `GET /connections`, `POST /types/{provider}/connections`, `PUT /connections/{id}`, `DELETE /connections/{id}`;
- `POST /connections/{id}/test`: one self-contained choice question; 204 or 503;
- `PUT /tasks/{flow}`: run the task on a connection.

`ChatModelFlowResponse` returns `systemOneConnectionId`; `PUT /api/chat/model-flows/{flow}` returns the task to a
model and clears it.

### Page

`/admin/system-one`, "Phân loại (System One)". The [prototype](mock.html) fixed the content (the task, the connections,
the types to add); the page is built from the Models page's patterns, not from the prototype's markup (owner,
2026-10-04):

- `PageHeader` with its one-line description; "Models by task" as a `Card` row with the task's title and description
  and the picker on the right, as the Models page lays out a task;
- the picker is assistant-ui's Model selector, as `ModelPicker` is, with one group "System One" for the usable
  connections ahead of one group per model provider. Choosing saves at once. A reasoning level is not offered here:
  a model chosen on this page runs at the task's default (off);
- "Available connections" as `ProviderCard` rows with the icon actions of a Models connection row (configure,
  delete). The data boundary sits on the address line, not beside the name, so a narrow screen wraps it under the
  address instead of over the actions. Delete is always offered; a connection in use is refused with its reason in
  the confirmation dialog;
- "Add a connection" as the Models page's provider grid; the dialog is `ConnectionDialog`/`ConnectionForm` with
  `DataBoundaryField`, and tests a saved connection only, as Voice and Web search do. A new connection of a type that
  already has one starts with a free name.

The Models page drops the row of this task, and the Chat settings link to the check's model opens this page.

## Behaviour that changes

- A Tenant that configures nothing sees no change in how a turn is checked.
- The check's task model moves from the Models page to the System One page.
- With a connection chosen, a check that fails is not retried, as today on a language model.

## Verification

- Unit: each adapter against a local server (request shape, answer read, key and no key, failed status, a response
  past the bound); the registry; the service (authorization, revision, name clash, delete while in use); the
  classifier's question and verdict; the turn with a connection (blocked, conversational, failed open, usage row).
- `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `OpenApiContractTest`, the migration test.
- Web: page tests with realistic fixtures, and screenshots reviewed.
- Measurement on self-written Vietnamese questions: accuracy (a blocked topic answered, a valid question blocked) and
  latency p50/p95 for the language model and each reachable connection.
- Staging: a connection added, tested and chosen; a blocked topic refused; the `unchecked` share before and after.

## Later candidates (noted 2026-10-04, not planned)

Where else a typed decision fits MemoryOS, for whoever picks the next use. None is measured or scheduled; real
Tenant data needs a self-hosted model first (MEM-222), and the Vietnamese measurement of this increment decides
which model is good enough.

| Use | Question | Needs beyond `SystemOneClient` |
| --- | --- | --- |
| Intent router (MEM-129) | choice | nothing |
| Outbound data gate (MEM-134): personal or sensitive content | yes/no or choice | a self-hosted model, since the content checked is the sensitive one |
| An answer supported by its cited passage (grounded Chat) | yes/no per sentence and passage | holding the answer or flagging it afterwards; `CitationGate` checks today that a citation exists, not that it supports the sentence |
| Filtering and reranking retrieved passages | yes/no, score | a place in the search pipeline, batched calls |
| Judge of the RAG benchmark | yes/no and score per criterion | criteria rewritten; scores stop being comparable with earlier runs |
| Tool selection as MCP tools grow (MEM-224) | choice plus "does any apply" | depends on how Embabel takes its tool list |
| Labels at ingestion (document type, sensitivity) | choice | no stated need yet |
| Which transcript stretch needs correcting | yes/no | only the selection; the proposal stays a language model's |

Generating text (titles, minutes, corrections, answers) is not a System One task. The library's own integrations
for several of these (`typesafe-spring-ai`: `JevJudge`, the advisors, `JevDocumentFilter`, `JevDocumentReranker`,
`JevToolIndex`) sit on Spring AI's `ChatClient` and tool SPIs, which Chat's Embabel turn does not run through.

## Out of scope

- Self-hosting Clef-flash or another model on the serving node ([MEM-222](https://linear.app/memory-os/issue/MEM-222)).
- The intent router (MEM-129) and the data gate (MEM-134); they will call `SystemOneClient`.
- A confidence threshold, a second classifier as fallback, batch calls and the SDK's Spring AI advisors.
