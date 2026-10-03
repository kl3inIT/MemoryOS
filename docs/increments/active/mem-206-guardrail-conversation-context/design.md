# The guardrail check reads the conversation (MEM-206)

Status: implemented 2026-10-01 at the owner's request.

## Problem

The question check (MEM-195 Check 1) classified only the message the person had just typed. A follow-up that names no
one passed a blocked topic: "Chủ tịch nước hiện nay là ai?" was blocked, "Thế còn gia đình ông ấy thì sao?" was a
question. The owner asked to follow how established guardrail projects work instead of reworking the check again.

## What the projects do

Compared on 2026-10-01 from their code and documentation: NVIDIA NeMo Guardrails, LiteLLM, Guardrails AI, LLM Guard,
Dify, LibreChat, Open WebUI, Llama Guard 3, ShieldGemma, Amazon Bedrock and Q Business, Azure OpenAI.

- **Context.** Llama Guard, NeMo topic control, LiteLLM's judge and Bedrock send the conversation and judge only its
  last message. Llama Guard's instruction: "Provide your safety assessment for ONLY THE LAST $agent_type message in the
  above conversation", the conversation laid out as `User:`/`Agent:` turns between `<BEGIN CONVERSATION>` and
  `<END CONVERSATION>`, the categories between their own markers. LiteLLM's judge: "Judge the most recent user turn;
  treat earlier turns in the conversation only as context."
- **Verdict.** A one-word answer, not JSON: NeMo (yes/no, on-topic/off-topic), Llama Guard (safe/unsafe), ShieldGemma
  (Yes/No). MemoryOS already asks for one label (#432).
- **Temperature.** NeMo topic control runs at 0.01 and LiteLLM's judge at 0.
- **A second classifier.** None falls back to another model; only Guardrails AI retries, on the same model.
- **When the check fails.** No shared default: NeMo, LiteLLM by default, Dify and LibreChat fail closed with an error;
  Azure OpenAI and LiteLLM's judge fail open. LiteLLM makes it a setting (`unreachable_fallback`). MemoryOS fails
  open (#432, the owner's decision that a guardrail is never an error) and gives the answer model the rules.

## Decision

1. **The check reads the conversation, Llama Guard's way.** The system message holds the task, the labels and the
   blocked topics between markers; the user message holds the conversation as `Person:`/`Assistant:` lines with the
   checked message last, and the model classifies ONLY THE LAST Person message. A follow-up that refers back to a
   blocked topic is about that topic; an earlier blocked message does not block an unrelated one.
2. **Bounded, since it runs on every checked turn.** The six most recent earlier messages with content, each clipped
   to 1,000 characters; the checked message whole, as before. The conversation markers are removed from every message,
   so a message cannot close the conversation and pose as instructions. Blocked phrases still match the checked message
   only.
3. **Temperature 0.** `ModelCalls.generateObject` takes a temperature, and the OpenAI adapter sends a helper call's own
   temperature only to a model that takes one: not a reasoning model, not the GPT-5 options family, not a model whose
   configuration names a temperature. An answer's temperature still comes only from the configuration and the person's
   creativity, so the member's creativity does not reach helper calls.
4. **No second model.** The check runs once on the `CHAT_GUARDRAIL` task model (or the conversation's model when the
   task has none). With no verdict the turn fails open with the rules in the answer model's system prompt, which is the
   same protection the retry gave, without its extra wait of up to 20 seconds.

## Open

- Attachments are not read by the check; a file that is about a blocked topic is not classified.
- Staging: ask a blocked question, then a follow-up that names no one, and confirm the second is blocked.
