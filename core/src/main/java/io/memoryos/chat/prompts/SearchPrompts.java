package io.memoryos.chat.prompts;

/**
 * Reference search prompts (onyx/prompts/search_prompts.py and filter_extration.py at 40eb240df), kept verbatim.
 * Only the output-format lines are adapted, because Embabel binds typed records instead of parsing free text.
 */
public final class SearchPrompts {
    private SearchPrompts() {}

    /** Arguments: current date. */
    public static final String SEMANTIC_SYSTEM = """
            You are an assistant that reformulates the last user message into a standalone, self-contained query suitable for \
            semantic search. Your goal is to output a single natural language query that captures the full meaning of the user's \
            most recent message. It should be fully semantic and natural language unless the user query is already a keyword query. \
            When relevant, you bring in context from the history or knowledge about the user.

            The current date is %s.
            """;

    /** Arguments: final user query. */
    public static final String SEMANTIC_TASK = """
            Given the chat history above (if any) and the final user query (provided below), provide a standalone query that is as
            representative of the user query as possible. In most cases, it should be exactly the same as the last user query. \
            It should be fully semantic and natural language unless the user query is already a keyword query. \
            Focus on the last user message, in most cases the history and extra context should be ignored.

            For a query like "What are the use cases for product X", your output should remain "What are the use cases for product X". \
            It should remain semantic, and as close to the original query as possible. There is nothing additional needed \
            from the history or that should be removed / replaced from the query.

            For modifications, you can:
            1. Insert relevant context from the chat history. For example:
            "How do I set it up?" -> "How do I set up software Y?" (assuming the conversation was about software Y)

            2. Remove asks or requests not related to the searching. For example:
            "Can you summarize the calls with example company" -> "calls with example company"
            "Can you find me the document that goes over all of the software to set up on an engineer's first day?" -> \
            "all of the software to set up on an engineer's first day"

            3. Fill in relevant information about the user. For example:
            "What document did I write last week?" -> "What document did John Doe write last week?" (assuming the user is John Doe)

            4. Remove source type scoping details — scoping is applied automatically, so naming a specific app or tool to search in only adds noise. For example:
            "Search Google Drive for the SLA doc" -> "SLA doc"
            "the refund policy in Zendesk" -> "refund policy"

            =========================
            CRITICAL: ONLY provide the standalone query in the requested structure and nothing else.

            Final user query:
            %s
            """;

    /** Arguments: current date. */
    public static final String KEYWORD_SYSTEM = """
            You are an assistant that reformulates the last user message into a set of standalone keyword queries suitable for a keyword \
            search engine. Your goal is to output keyword queries that optimize finding relevant documents to answer the user query. \
            When relevant, you bring in context from the history or knowledge about the user.

            The current date is %s.
            """;

    /** Arguments: final user query. */
    public static final String KEYWORD_TASK = """
            Given the chat history above (if any) and the final user query (provided below), provide a set of keyword only queries that can
            help find relevant documents. Provide each query as a separate entry (where each query consists of one or more keywords). \
            The queries must be purely keywords and not contain any natural language. \
            Each query should have as few keywords as necessary to represent the user's search intent.

            Guidelines:
            - Do not provide more than 3 queries.
            - Do not replace or expand niche, proprietary, or obscure terms
            - Do not include source type scoping details (e.g. naming an app or tool like Zendesk, Google Drive, Slack) as keywords — scoping is applied automatically.
            - Focus on the last user message, in most cases the history and any extra context should be ignored.

            =========================
            CRITICAL: ONLY provide the keyword queries in the requested structure and nothing else.

            Final user query:
            %s
            """;

    /** Arguments: conversation history, current cycle queries, previous cycles, valid sources, last user query. */
    public static final String SOURCE = """
            You scope an internal search to its relevant sources. When the conversation EXPLICITLY \
            names source(s) to search, scope to them; when it names none, return [] (search every \
            source). You scope only by source — other scoping is handled by other systems. The system \
            runs multiple cycles, and the queries and sources of previous cycles are provided as \
            context.

            ## Guidance

            Scope to a source when it is EXPLICITLY named — in this cycle's queries, or in an earlier \
            turn that this cycle continues. NEVER infer a source from the query's topic (e.g. an HR or \
            billing query is not a source). If no source is named, return [].

            A source named in an earlier turn still applies to a same-topic follow-up that names no new \
            source — keep scoping to it.

            When source(s) ARE named, the phrasing decides the mode:

            - COMBINED — one or more named sources with NO fallback order ("in Google Drive"; "search \
            A and B"; "check both A and B"): scope to all of them every cycle, regardless of previous \
            cycles. A single named source is COMBINED — scope to it.

            - BACKOFF ("check A first, then B", "try A; if nothing, then B" — an order): scope to ONE \
            source per cycle. By DEFAULT ADVANCE — scope to the first named source NOT in any previous \
            cycle's searched_sources; a reworded retry of the same search keeps advancing. BUT if this \
            cycle's queries are about a clearly DIFFERENT topic than the previous cycle's, re-search the \
            source the previous cycle used — it has not been searched for this new topic. Once all named \
            sources have been tried, scope to all of them.

            Only scope to sources listed in the Valid sources section below. If a named source is not \
            listed there, ignore it and scope to the named sources that ARE listed; return [] only when \
            none of the named sources are listed.

            ## Conversation history

            %1$s

            ## Current cycle queries

            %2$s

            ## Previous cycles of this user query

            %3$s

            ## Valid sources

            %4$s

            ## Guidance reminder

            COMBINED ("A and B"): scope to all named sources, every cycle.
            BACKOFF ("A first, then B"): by DEFAULT ADVANCE to the first named source not in previous \
            cycles' searched_sources (a reworded retry keeps advancing). If this cycle's queries are \
            about a clearly DIFFERENT topic than the previous cycle's, re-search the source the previous \
            cycle used.
            If no source is named anywhere in the conversation, return [].

            ## Output format

            Output the list of sources in the requested structure. FILE means uploaded files; GOOGLE_DRIVE \
            means Google Drive.

            Do not include any formatting, explanations, or other text aside from the list. Provide an \
            empty list [] if no source should be scoped this cycle.

            ## Query reminder

            The user's query is:
            %5$s

            CRITICAL: output only the list of sources.
            """;

    /** Arguments: conversation history, current day, last user query. */
    public static final String TIME = """
            You scope an internal search to a time filter, from the user's conversation. When the \
            conversation EXPLICITLY refers to a time the documents should fall within, decide WHICH date \
            the time is about ("created" vs "updated") and set the (start, end) bounds; when it refers to \
            none, return "updated (None, None)" (search across all time). You scope only by time.

            ## Guidance

            Set a time filter when a time is EXPLICITLY referenced — in the latest message, or in an \
            earlier turn it continues. NEVER infer a time from the topic alone. A date that names the \
            subject or title of the document sought ("the 2020 GDPR docs", "the FY21 plan") is NOT a \
            filter — it says WHAT the document is, not WHEN it was written; let content search match it. \
            If no time is referenced, return "updated (None, None)".

            When a time IS referenced, first decide WHICH date it is about: use "created" when the time \
            is about when the document was created ("created", "sent", "posted", "published", …); \
            otherwise use "updated" — for a change or activity ("edited", "changed", "closed", …) and \
            for anything not clearly about creation. When unsure, use "updated".

            Then the phrasing decides the bounds:

            - LOWER BOUND ONLY — an open-ended time toward now ("since March", "recently", "in the last \
            2 weeks"). Set start; leave end None — it has no upper bound, so do NOT set end to today.

            - UPPER BOUND ONLY — an open-ended time toward the past ("before 2023", "older than \
            January", "more than 20 weeks ago"). Set end; leave start None.

            - BOTH BOUNDS — a completed, named calendar period ("last quarter", "last January", "Q1 \
            2025", "in 2022", "between March and June", a single day like "March 25 2024") or a numeric \
            range ("10 to 15 weeks ago"). A named period is NOT a rolling duration — "last quarter" is \
            the previous calendar quarter (both bounds), not the last 3 months. Set start to its first \
            day / larger offset, end to its last day / smaller offset.

            - NO BOUND — a vague preference for fresh results with no actual time ("the latest", "most \
            recent"). Return "updated (None, None)".

            ## Conversation history

            %1$s

            ## Current date

            Today is %2$s. Use a token "-P<N><U>" — a signed ISO-8601 duration where \
            the leading minus means "before today" and U is D=days, W=weeks, M=months, Y=years (e.g. \
            -P15W, -P5M, -P30D) — ONLY for a numeric offset — a number the message states followed by a \
            time unit ("15 weeks ago", "the last 5 months", "30 to 45 days ago"); then never compute the \
            date, the system resolves the token against today. A month or year NAME ("March 2024", "Q1 \
            2025", "2022") is NOT a numeric offset — resolve it to an absolute YYYY-MM-DD date yourself, \
            never a token.

            ## Guidance reminder

            FIELD: "created" only when the phrasing is clearly about creation; otherwise "updated" (the \
            default).
            LOWER / UPPER BOUND: an open-ended time sets one bound and leaves the other None — one \
            toward now ("the last 2 weeks") leaves end None, not today.
            BOTH BOUNDS: a named calendar period ("last quarter", "in 2022") or a numeric range ("10 to \
            15 weeks ago") sets both bounds — a named period is never a rolling duration.
            A month or year NAME is an absolute date, never a token. NEVER filter on a date that names \
            the document's subject/title, and return "updated (None, None)" when no time is referenced.

            ## Output format

            Output ONLY the decision in the requested structure as field, start and end. field is "created" or \
            "updated". start and end are each a date "YYYY-MM-DD", a token "-P<N><U>" (a signed ISO-8601 \
            duration before today, e.g. -P15W), or null; bounds are inclusive, and null means no bound on \
            that side.

            Examples:
            - "in the last 2 weeks" → updated (-P2W, None)
            - "10 to 15 weeks ago" → updated (-P15W, -P10W)
            - "more than 20 weeks ago" → updated (None, -P20W)
            - "in the last 5 months" → updated (-P5M, None)
            - "since March 2025" → updated (2025-03-01, None)
            - "created in 2022" → created (2022-01-01, 2022-12-31)
            - "posted before 2023" → created (None, 2022-12-31)
            - "in January 2025" → updated (2025-01-01, 2025-01-31)
            - "the 2020 GDPR docs" → updated (None, None)
            - "the latest updates" → updated (None, None)

            Do not include any formatting, explanations, or other text aside from the decision.

            ## Query reminder

            The user's latest message is:
            %3$s

            CRITICAL: output only the decision.
            """;

    /** Arguments: maximum sections, formatted document sections, user query. */
    public static final String SELECT = """
            Select the most relevant document sections for the user's query (maximum %1$d).

            # Document Sections
            ```
            %2$s
            ```

            # User Query
            ```
            %3$s
            ```

            # Selection Criteria
            - Choose sections most relevant to answering the query, if at all in doubt, include the section.
            - Even if only a tiny part of the section is relevant, include it.
            - It is ok to select multiple sections from the same document.
            - Consider indirect connections and supporting context to be valuable.
            - If the section is not directly helpful but the document seems relevant, there is an opportunity \
            later to expand the section and read more from the document so include the section.

            # Output Format
            Return ONLY section_ids in the requested structure, ordered by relevance:
            [most_relevant_section_id, second_most_relevant_section_id, ...]
            """;

    /** Arguments: document title, section above, main section, section below, user query. */
    public static final String CLASSIFY = """
            Analyze the relevance of document sections to a search query and classify according to the categories \
            described at the end of the prompt.

            # Document Title / Metadata
            ```
            %1$s
            ```

            # Section Above:
            ```
            %2$s
            ```

            # Main Section:
            ```
            %3$s
            ```

            # Section Below:
            ```
            %4$s
            ```

            # User Query:
            ```
            %5$s
            ```

            # Classification Categories:
            **NOT_RELEVANT**
            - Main section and surrounding sections do not help answer the query or provide meaningful, relevant information.
            - Appears on topic but refers to a different context or subject (could lead to potential confusion or misdirection). \
            It is important to avoid conflating different contexts and subjects - if the document is related to the query but not about \
            the correct subject. Example: "How much did we quote ACME for project X", "ACME paid us $100,000 for project Y".

            **MAIN_SECTION_ONLY**
            - Main section contains useful information relevant to the query.
            - Adjacent sections do not provide additional directly relevant information.

            **INCLUDE_ADJACENT_SECTIONS**
            - The main section AND adjacent sections are all useful for answering the user query.
            - The surrounding sections provide relevant information that does not exist in the main section.
            - Even if only 1 of the adjacent sections is useful or there is a small piece in either that is useful.
            - Additional unseen sections are unlikely to contain valuable related information.

            **FULL_DOCUMENT**
            - Additional unseen sections are likely to contain valuable related information to the query.

            ## Additional Decision Notes
            - If only a small piece of the document is useful - use MAIN_SECTION_ONLY or INCLUDE_ADJACENT_SECTIONS, do not use NOT_RELEVANT.
            - If the document is on topic and provides additional context that might be useful in \
            combination with other documents - use MAIN_SECTION_ONLY, INCLUDE_ADJACENT_SECTIONS or FULL_DOCUMENT, do not use NOT_RELEVANT.

            CRITICAL: ONLY output the classification of the situation most applicable to the query and sections provided, in the requested structure.
            """;
}
