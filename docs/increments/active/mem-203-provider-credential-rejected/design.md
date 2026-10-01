# A refused provider credential, named

Status: implemented 2026-10-01. Linear: [MEM-203](https://linear.app/memory-os/issue/MEM-203).

## Problem

On staging (2026-10-01 03:11 and 11:00 UTC) the OpenAI provider refused its API key with HTTP 401. Every chat turn
on its model failed within seconds, but the reader was told something false:

- the answer read "Câu trả lời bị gián đoạn. Nội dung đã nhận được giữ lại.", although nothing was interrupted and
  nothing was kept;
- the composer showed "Chưa xác nhận được trạng thái câu trả lời", although the server had committed the failure.

The stored code was `CHAT_EXECUTION_FAILED`, so nothing told an administrator that the key was the cause.

## Decision

**One reported code.** `TurnFailure.PROVIDER_CREDENTIAL_REJECTED` (`CHAT_PROVIDER_CREDENTIAL_REJECTED`, the code
the Models page already returns when a provider refuses a key) is stored when the provider refuses the credential
in any call of the turn: the answer, research and helper calls, and the guardrail check, which used to fail closed
as an unreported `CHAT_PROVIDER_UNAVAILABLE`.

**The adapter decides what a refused credential is.** Following
[ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md), the turn does not know SDK
exception types. The adapter puts a rule on the binding it builds (`ModelBinding.credentialRejection`, default
none); the OpenAI adapter's rule is HTTP 401 or 403 from `OpenAIServiceException` anywhere in the cause chain
(`OpenAiFailures`). The Responses model, which turns a stream error into a `TurnFailure` itself, applies the same
rule. No provider message is read or logged.

**Words by role.** A model manager (`MODELS_MANAGE`) reads that the provider rejected the model's API key, with a
link to `/admin/models`; anyone else reads that the model is unavailable and to choose another or tell an
administrator. No key or provider detail reaches either.

**Shown as an error.** Owner decision 2026-10-01: the failure sits inside the answer as a red banner with a short
title and a detail, as Onyx's `ErrorBanner` does, instead of a line of muted text. It is assistant-ui's Error state
element (`ErrorState`), which the project already adapted, now tinted red as the registry ships it, through the status
danger tokens; its other uses (form dialogs, the file reader, artifacts, the composer notice) are errors as well.
`MessagePrimitive.Error` is not used: it renders only while the runtime holds an error status, so a failed answer
reloaded from history would show nothing. The unconfirmed notice above the
composer stays for a connection whose outcome is unknown.

**Only true sentences.** "Interrupted, content kept" is shown only when the failed answer kept text; otherwise the
answer says no answer could be generated. A turn the server committed as FAILED ends its stream with a known error
(`COMMITTED_FAILURE`), and the runtime no longer reads it as an unconfirmed reply, so the composer shows no notice.

**A question the guardrail could not check.** Owner decision 2026-10-01: a guardrail check that fails for any other
reason, such as a verdict in the wrong shape (`InvalidLlmReturnFormatException`, seen with a 9Router model the same
day), stores `CHAT_GUARDRAIL_UNAVAILABLE` instead of the generic code. Everyone reads "Không kiểm tra được câu hỏi";
a model manager gets a link to choose the question check's task model (#424), anyone else is told to try again later
or tell an administrator. The credential check reads the binding of the model that ran the check, which since #424
can differ from the conversation model.

**Not in scope.** Marking a provider whose key was refused on the Models page (item 4 of the issue) waits for an
owner decision.
