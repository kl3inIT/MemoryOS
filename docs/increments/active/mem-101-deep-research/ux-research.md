# Deep research UX patterns in other assistants (2026-09-16)

Onyx is the behavior reference for this increment. This note records what other shipped assistants do, from Mobbin screenshots of their live web UIs, so the MemoryOS choices are deliberate rather than inherited. Each row links the screen it came from.

## What the others show

| Product | Observed | Screen |
| --- | --- | --- |
| Google Gemini | The plan is a card with **Edit plan** and **Start research** before any work runs. While running, a side panel lists every source being read with favicon and title under "Researching 45 websites…". The finished report opens in that panel with Contents, Share & Export and Create. | [plan card](https://mobbin.com/screens/6d6bf893-052b-44a7-a382-01ce8a86af65), [sources panel](https://mobbin.com/screens/e9edea61-c4d0-4d82-9435-df4844319336) |
| ChatGPT | A right panel with two tabs, **Activity** and **23 Sources**. Activity is a narrative list ("Read irci.jp", "Searched Japan matcha certification"), closing with "Research completed in 5m · 23 sources". | [activity and sources](https://mobbin.com/screens/73833b79-1dd5-4354-8fc4-a2e99c33a75e) |
| Microsoft Copilot | An in-thread Deep Research card: title, a collapsible "I'm browsing and analysing sources" with the current query as a chip, the next step, and **Est time: ~10 min**. The composer is disabled with "Working on your research—start a new conversation to keep talking!". | [research card](https://mobbin.com/screens/65c9ea13-527d-4184-96bd-421fd422334b) |
| Mistral Le Chat | Header "Researching…" with a **source count and avatars**, a coloured bar of the sources found, streamed reasoning, then **15 min estimate** and a **Cancel** button inside the block. | [running research](https://mobbin.com/screens/181ce284-3dcd-4d44-bb1f-368d2e5db997) |
| Perplexity | Steps as expandable rows ("Searching for GE Healthcare diagnostic imaging patents") with their hits, a Thinking row, and the report in a side document with Export. | [steps](https://mobbin.com/screens/75d9f23e-7934-44c7-b1fd-77a77ba712e6) |
| Elicit | A status rail as a checklist with counts: Gather papers (50 found), Screen papers (10 included), Extract data (50 data points), Generate report, plus "0:27 elapsed (5 minutes estimated)". | [status rail](https://mobbin.com/screens/232b3136-ccbb-42e7-a45c-412ea436f330) |
| Rox | "Step 1 of 7" with a progress bar and **3 min left**, each step listing its queries as chips. | [step progress](https://mobbin.com/screens/107c729c-a68d-48e3-8ece-51d43eff4215) |
| Exa | "Spawned 3 subtasks" with per-subtask reasoning and completion, then the final report with **Total Cost $0.0793**. | [subtasks](https://mobbin.com/flows/dd0c371e-d6cf-4066-972a-1fac164505a5) |

## What MemoryOS has and lacks

MemoryOS shows the plan, one tab per parallel agent with its task, tool steps with query chips, thoughts and an intermediate report, all in the MEM-100 activity timeline, and restores them from history.

| Pattern | Present in | MemoryOS | Decision |
| --- | --- | --- | --- |
| Source count while running and at the end | ChatGPT, Mistral, Gemini | Absent; sources appear only under the answer | Adopt: the turn already streams `SOURCE` events, so a count is available |
| Elapsed time, and a bound the user can read | ChatGPT, Copilot, Mistral, Elicit, Rox | Absent, although agent `durationMs` is persisted and streamed | Adopt elapsed; a remaining-time estimate is not derivable from the Onyx loop and would be invented |
| Progress across steps ("Step 1 of 7", checklist with counts) | Rox, Elicit | Absent; cycles are not surfaced | Adopt partially: orchestrator cycles are bounded (8, or 4 for reasoning models), so "cycle n" is honest; a percentage is not |
| Cancel inside the research block | Mistral | Chat Stop exists in the composer only | Consider; Stop already works, this is placement |
| Sources as their own tab beside activity | ChatGPT, Gemini | Sources render under the answer (MEM-87) | Skip: the existing Sources row is the Chat-wide contract |
| Plan approval and editing before the run | Gemini | Streamed plan, no approval step | Skip: the owner accepted "no plan approval step" on 2026-09-15; recorded here as the rejected alternative, revisit only on request |
| Report in a side document with export | Gemini, Perplexity, Mistral | Inline answer | Skip: the owner accepted an inline report |
| Composer disabled with an explanation while researching | Copilot | Composer stays usable; a second send is rejected by the run lease | Consider: the rejection message is the only feedback today |
| Cost of the run shown to the user | Exa | Cost is persisted but not shown | Skip: Chat shows cost nowhere today; a Chat-wide decision, not a research one |

## Scope taken from this note

1. Done (2026-09-16): the group header shows the source count with elapsed time while the turn runs, and the step count with the source count once it ends; each agent tab shows a spinner while it runs and its panel says how long it ran.
2. Done (2026-09-16): a cycle label names each group of agents when more than one cycle ran.
3. Done (2026-09-16): a long plan or intermediate report is clamped with a fade and a "Xem thêm" reveal, as Onyx clamps with `ExpandableTextDisplay`. The reveal appears only when the text actually overflows, measured after render.

Everything else stays out until the owner asks for it.
