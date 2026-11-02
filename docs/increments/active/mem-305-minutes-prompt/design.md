# MEM-305: the minutes prompt written by practice

## Problem

The [minutes benchmark](../../../tools/minutes-benchmark/README.md) (MEM-304) found the minutes keep about a third of what a careful reading of the meeting holds: 0.34 of the actions and 0.34 of the decisions, while 0.72 and 0.79 of what they write is in the key. They are short rather than wrong. The prompt grew one rule per fault over MEM-300 and MEM-301 and only says what to leave out: "never invent", "an action needs an explicit assignment", "never assign work to a group". Nothing asks for completeness, every rule is absolute, and the evidence for an item is the last thing the model writes.

## What the references do

Read on 2 November 2026 from the clones in `.tmp` and the providers' current guides:

| Practice | Who | Ours before |
| --- | --- | --- |
| Ask for every item of each kind beside what does not count ("do not omit any explicit decision … commitment"; "do not turn proposals into commitments") | anarlog `enhance.system.md.jinja`, Nojoin `meeting_notes.py`, silent-notetaker, call.md `summary-generator.service.ts`, Meetily chunk prompt | Exclusions only |
| Owner: a person named as doing it or who committed themselves ("I'll send it"); otherwise none, and the item is kept | opennotetaker `note-summary/src/prompt.rs`, Nojoin ("A single named person, or Unassigned"), anarlog | An explicit assignment required; unowned work dropped |
| Evidence first: quote before the answer; structured output writes properties in schema order | Anthropic long-context tips ("quote relevant parts first"), OpenAI Structured Outputs (order preserved), ghiam-pro and opennotetaker (every item traceable) | Quote and line generated last |
| Long input first in its own block, the task restated after it | Nojoin (closing instruction, fenced transcript), anarlog (template after transcript), Anthropic (up to 30% better), OpenAI GPT-4.1 guide, Gemini long-context guide | All instructions before, nothing after |
| Absolute rules only for true invariants; give the reason; no conflicting rules; field formats in the schema, not repeated in the prompt | OpenAI GPT-5 and GPT-5.5 guides, Anthropic | Many "never", no reasons, formats in the prompt |
| Long transcripts extracted in chunks and merged, because finding many items in long input loses recall | opennotetaker, silent-notetaker, Meetily; Gemini and OpenAI long-context notes | One call over up to 57,000 characters |

## Decision

Two steps, each measured on the benchmark against the MEM-304 baseline, so each change shows what it is worth.

1. **The prompt and the schema, still one call.** The instructions say what a careful note-taker records and why: every decision (a choice settled, a proposal once accepted) and every piece of work someone will do after the meeting (taken on, asked and not refused, an agreed next step), with what does not count (an idea nobody took up, work already done, a wish nobody takes on). An owner is the person named as doing it or who took it on; work for a group or nobody clear keeps no owner and stays. Support by the transcript stays the one firm rule, with its reason. The schema carries each field's description and writes items before the summary, and each item's quote and line before its words and owner. The transcript sits in a `<transcript>` block and the task is said again after it. `MinutesChecks` (MEM-300) and owner linking (MEM-301) are unchanged.
2. **Extraction per chunk**, if step 1 leaves recall low: a separate change.

A change is kept only when it raises recall without a large fall in precision.
