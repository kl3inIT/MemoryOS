# MEM-300: the minutes are checked after the model answers

## Problem

The minutes are stored as the model returned them, after `TranscriptSummarizer.clean` drops items with no text, unknown lines and repeated topic lines. An audit of the 30 meetings on staging (22/09–31/10/2026, 10,502 utterances, 27 with minutes) compared every item with its transcript:

- **Owners.** Of 119 actions, 27 name one person. 45 name a speaker label ("Người nói 2"), 16 a group or organisation ("các cháu", "nhóm phát triển", "đơn vị quản lý dự án", two people joined by "với"), 31 none. The prompt already says "Never assign work to a group"; the model breaks it, and labels are not covered at all. Only 3 of the 30 meetings have named speakers, so the model mostly sees labels and copies them.
- **Quotes.** Of 175 decision and action quotes, 148 sit in the cited line, 19 span two to four neighbouring lines or sit one line off, and 8 are nowhere near it. The page shows the quote under the time of the cited line, so a wrong line sends the reader to the wrong place.
- **Duplicates.** 16 items cite a line another item already cites: one sentence written up as both a decision and an action, or the same action twice.
- **A correction that repeats a word.** Of the 7 corrections applied on staging, the 3 that broke words ("token" → "tokenen") predate [#348](https://github.com/kl3inIT/MemoryOS/pull/348), which widened stretches to whole words. One of the 4 since then still broke its line: the stretch was "Google", the model answered "Google Drive", and the next word was already "Drive", giving "One Google Drive Drive". `TranscriptCorrector.clean` rejects an answer several times the stretch's length, but not one that runs into its neighbour by a word.

## Decision

Deterministic checks between the model's answer and storage, in the `ai` module where the answer is already cleaned. No prompt change, no new field, no API or page change.

1. **An owner is one person or nobody.** `owner` becomes null when it
   - is a speaker label as the transcript showed it to the model (`Người nói N`, `Speaker N`);
   - names a group: a word such as *nhóm, team, đội, phòng, ban, bộ phận, các bạn/em/cháu/anh/chị, bọn em/anh, chúng em/tôi/ta, anh em, mọi người, cả hai, tất cả, đơn vị, nhà thầu, công ty, everyone, both, we*;
   - names several people: joined by *và, với, and*, a comma, `/` or `&`.

   The action stays; only the owner goes. A wrong name is worse than none: the owner can fill one in, and nothing tells a reader that "Người nói 2" is not a person.
2. **A quote is found in the transcript or dropped.** Quote and lines are compared after Unicode NFC, lower case, collapsed whitespace and without punctuation and quotation marks. When the cited line holds the quote, nothing changes. Otherwise the check looks, nearest first, at single lines up to two either side, then at two or three consecutive lines starting there; the first match moves the item's line to where the quote starts. A quote the model shortened (three or more words, every one of them in one of those lines in the same order, spread over at most twice its length or eight words more) becomes the line's own words from its first word to its last. With no match the quote is dropped and the line kept. An item with no usable line whose quote matches exactly one place in the transcript takes that line.
3. **One sentence, one item.** Items are compared after the quote check, with quotes matched as whole words. A decision on an action's line is dropped when the two rest on one sentence (one quote holds the other) or their words overlap 0.6 or more; a line often holds several sentences, so the line alone is not enough. Two items of one kind on the same line are kept apart only when their words differ (token overlap below 0.6). Two items of one kind with near-identical words (overlap 0.8 or more) are one item wherever they point, unless they are actions with two different owners. The first one the model wrote stays and takes from the repeat what it lacked: an owner, a deadline, a quote with its line.
4. **A correction may not run into its neighbour.** `TranscriptCorrector.clean` drops a proposal that, put in, gives its sentence more places where a word or a run of up to three words is said twice in a row than the sentence had. Only the stretch's own sentence is compared, so a word ending one sentence and opening the next is no repeat, and a repeat the speaker made may stay but not grow. Such a proposal is never shown, as if the model had kept the words.

## Choices made

- **Clear the owner rather than rewrite it.** Resolving "Người nói 2" to a name needs the speaker named, and a group has no single owner to choose. Both belong to later work (naming speakers after a meeting, the prompt). Clearing is the one answer the checks can give without guessing.
- **Word lists, not a model, for groups.** The call already happened; a second model call to judge owners doubles the cost of every minutes run for a rule a short list catches. The list may clear a rare real name that is also one of its words; the measurement below lists every cleared owner so that is visible.
- **Drop a quote rather than keep an unverified one.** The item still says what was decided and still points at a line; only the claim "these exact words were said here" goes.
- **Restore a shortened quote rather than drop it.** Most quotes that missed their line on staging had every word in it, in order, with words left out between them. The line's own stretch is what was said and still small; dropping those would have lost 4 of the 10 quotes that missed.
- **Keep the action, drop the decision** when both rest on one sentence: the action holds the owner and the deadline, and the decision's words are usually the action restated.
- **In `TranscriptSummarizer.clean`, not in `MeetingMinutesService`.** The checks need the numbered lines the model read, which `TranscriptSummarizer` holds; the meeting service only maps line numbers to utterance ids afterwards. The checks live in one package-private class beside it so each rule is tested on its own.
- **Stored minutes stay as they are.** A rerun (`POST /api/meetings/{id}/minutes`, the page's *Viết lại*) writes them through the checks. A one-off rewrite of stored minutes would be a temporary operational surface ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).

## Measurement

The checks are measured on the real staging meetings before and after, with no change to staging data:

- **Stored minutes.** The stored minutes of the 23 meetings left after seven test and duplicate recordings were deleted, exported read-only, run through the same rules.
- **Fresh minutes through 9Router.** The 23 transcripts sent with the exact `MEETING_MINUTES` prompt and transcript format to `cx/gpt-6-luna` on 9Router (the benchmark judge's endpoint and key, no OpenRouter spend), then through the rules. This separates what the checks fix from what one run of the model happens to do.
- **What is reported:** owners that are one person, labels and groups left (target 0), quotes in their cited line, items cleared or dropped, and the full list of cleared owners and dropped quotes for review.

The measurement script mirrors the Java rules; the unit tests pin the Java rules on the same cases. After deployment, minutes rerun on staging go through the Java code itself.

## Not in scope

- The prompt (owner = the one who does the work, decisions that were reversed, talk after the meeting closes, missed requests), quotes longer than 25 words, and an organisation glossary: the next step, measured with a model judge.
- Naming speakers after a meeting and rewriting the minutes with the names.
- Ending recordings nobody stopped (3 of the 30 meetings never ended and have no minutes).
