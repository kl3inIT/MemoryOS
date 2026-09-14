package io.memoryos.chat.tools;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.tool.callback.AfterToolCallContext;
import com.embabel.agent.api.tool.callback.BeforeToolCallContext;
import com.embabel.agent.api.tool.callback.ToolCallInspector;
import com.embabel.agent.api.tool.Tool.Result;
import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import io.memoryos.chat.ChatSearchEvent;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.prompts.SearchPrompts;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchDocumentUnavailableException;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchQuery;
import io.memoryos.retrieval.SearchRequestException;
import io.memoryos.retrieval.SearchResults;
import io.memoryos.retrieval.SearchSection;
import io.memoryos.retrieval.SearchFilters;
import io.memoryos.retrieval.SearchTasks;
import io.memoryos.retrieval.SearchTimings;
import io.memoryos.retrieval.SearchTimings.Stage;
import io.memoryos.retrieval.SearchUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.time.LocalDate;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.time.ZoneOffset;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import org.jspecify.annotations.Nullable;

/** One per turn. Embabel owns inference/tool continuation; this tool owns grounded retrieval. */
public final class SearchTool implements ToolCallInspector, AutoCloseable {
    private final DocumentSearchService search;
    private final ActorId actor;
    private final PromptRunner selectionRunner;
    private final TokenCountEstimator tokens;
    private final ChatSearchProperties limits;
    private final Runnable checkActive;
    private final IntSupplier availableTokens;
    private final Consumer<ChatSearchEvent> events;
    private final io.memoryos.chat.ChatEvidence evidence;
    private String toolCallId = "";
    private int calls;
    private boolean failed;
    private final SearchTasks.Scope work;
    private final Disposable cancellation;
    private final List<Message> history;
    private final String question;
    private @Nullable QueryExpansion queryExpansion;
    private final Instant deadline;
    private final SearchTimings timings;
    private final Set<UUID> allowedSourceIds;
    private boolean scopeDecisionSettled;
    private boolean timeDetected;
    private SearchFilters timeFilters = SearchFilters.NONE;
    private final List<SearchCycle> searchCycles = new ArrayList<>();
    private String scopeNote = "";
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final java.util.regex.Pattern RELATIVE_BOUND =
            java.util.regex.Pattern.compile("^-?\\s*P\\s*(\\d+)\\s*([DWMY])$", java.util.regex.Pattern.CASE_INSENSITIVE);

    public SearchTool(DocumentSearchService search, ActorId actor, PromptRunner selectionRunner,
                      TokenCountEstimator tokens, ChatSearchProperties limits, Runnable checkActive,
                      IntSupplier availableTokens, Consumer<ChatSearchEvent> events, Mono<?> cancellation, List<Message> messages,
                      Instant deadline, SearchTimings timings) {
        this(search, actor, selectionRunner, tokens, limits, checkActive, availableTokens, events, cancellation, messages, deadline, timings, List.of());
    }

    public SearchTool(DocumentSearchService search, ActorId actor, PromptRunner selectionRunner,
                      TokenCountEstimator tokens, ChatSearchProperties limits, Runnable checkActive,
                      IntSupplier availableTokens, Consumer<ChatSearchEvent> events, Mono<?> cancellation, List<Message> messages,
                      Instant deadline, SearchTimings timings, List<UUID> allowedSourceIds) {
        this(search, actor, selectionRunner, tokens, limits, checkActive, availableTokens, events, cancellation,
                messages, deadline, timings, allowedSourceIds, new io.memoryos.chat.ChatEvidence());
        evidence.publishTo(events);
    }

    public SearchTool(DocumentSearchService search, ActorId actor, PromptRunner selectionRunner,
                      TokenCountEstimator tokens, ChatSearchProperties limits, Runnable checkActive,
                      IntSupplier availableTokens, Consumer<ChatSearchEvent> events, Mono<?> cancellation, List<Message> messages,
                      Instant deadline, SearchTimings timings, List<UUID> allowedSourceIds, io.memoryos.chat.ChatEvidence evidence) {
        this.evidence = evidence;
        this.allowedSourceIds = Set.copyOf(allowedSourceIds);
        this.search = search; this.actor = actor; this.selectionRunner = selectionRunner; this.tokens = tokens;
        this.limits = limits;
        this.work = new SearchTasks.Scope(limits.cleanupTimeout());
        this.checkActive = () -> {
            work.checkActive();
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Search stopped");
            checkActive.run();
        };
        this.availableTokens = availableTokens;
        this.events = event -> { this.checkActive.run(); events.accept(event); };
        this.deadline = deadline;
        this.timings = timings;
        this.history = messages.stream().filter(m -> !(m instanceof SystemMessage)).toList();
        this.question = history.stream().filter(UserMessage.class::isInstance).map(Message::getContent)
                .reduce((ignored, current) -> current).orElseThrow(() -> new IllegalArgumentException("Missing user question"));
        this.cancellation = cancellation.subscribe(ignored -> work.cancel());
    }

    @Override public void beforeToolCall(@NonNull BeforeToolCallContext context) {
        checkActive.run();
        toolCallId = context.getToolCall().getId();
        failed = false;
        progress(ChatSearchEvent.Stage.STARTED);
    }

    @Override public void afterToolCall(@NonNull AfterToolCallContext context) {
        checkActive.run();
        progress(failed || context.getResult() instanceof Result.Error
                ? ChatSearchEvent.Stage.FAILED : ChatSearchEvent.Stage.COMPLETED);
    }

    public record SemanticQuery(String query) {}
    public record KeywordQueries(List<String> queries) {}
    public record Selection(List<Integer> sections) {}
    public record ContextSelection(Expansion classification) {}
    public record SourceChoice(List<SourceType> sources) {}
    public record TimeChoice(@Nullable String field, @Nullable String start, @Nullable String end) {}
    public enum Expansion { NOT_RELEVANT, MAIN_SECTION_ONLY, INCLUDE_ADJACENT_SECTIONS, FULL_DOCUMENT }
    private record QueryExpansion(String semantic, List<String> keywords) {}
    private record SearchCycle(int cycleNumber, List<String> queries, List<SourceType> searchedSources) {}
    private record Preparation(QueryExpansion expansion, SearchFilters filters, boolean reuseExpansion) {}

    @LlmTool(description = "Search authorized organization documents. Returns evidence with citation numbers; empty evidence means no grounded answer is available.")
    @SuppressWarnings("unused") // Invoked by the native Embabel method tool, verified through Chat HTTP tests.
    public String searchKnowledge(
            @LlmTool.Param(description = "One to three focused search queries covering the user's question; preserve exact names and resolve references from history") List<String> queries,
            @LlmTool.Param(description = "Optional explicit source types and document creation/update intervals from the user. Null when unspecified. Dates are UTC instants with inclusive bounds; these never grant access.", required = false)
            @Nullable SearchFilters requestedFilters) {
        try (var _ = work.enter()) {
            scopeNote = "";
            String response = executeSearch(queries, requestedFilters);
            return scopeNote.isEmpty() ? response : response + "\n" + scopeNote;
        }
    }

    private String executeSearch(List<String> queries, @Nullable SearchFilters requestedFilters) {
        checkActive.run();
        if (++calls > limits.maxCalls()) return "Search call limit reached. Answer only from evidence already returned.";
        if (queries == null || queries.isEmpty() || queries.size() > 3)
            return "Invalid search arguments: provide 1-3 focused queries.";
        try {
            queries.forEach(q -> new SearchQuery(q, false, .7));
        } catch (SearchRequestException invalid) { return "Invalid search query. Each query must contain 1-2000 characters."; }
        try {
            var scope = search.scope(actor);
            if (!allowedSourceIds.isEmpty()) scope = new SourceSearchScope(scope.tenant(), scope.actor(), scope.sources().entrySet().stream()
                    .filter(entry -> allowedSourceIds.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue)),
                    scope.accessTokens());
            var preparation = prepare(queries, scope, requestedFilters == null ? SearchFilters.NONE : requestedFilters);
            var expansion = preparation.expansion();
            var filters = preparation.filters();
            var requests = new LinkedHashMap<String, SearchQuery>();
            if (preparation.reuseExpansion()) addQuery(requests, new SearchQuery(expansion.semantic(), false, 1.3));
            queries.forEach(q -> addQuery(requests, new SearchQuery(q, false, .7)));
            if (preparation.reuseExpansion()) expansion.keywords().forEach(q -> addQuery(requests, new SearchQuery(q, true, 1)));
            if (question.length() <= 2000) addQuery(requests, new SearchQuery(question, false, .5));
            events.accept(new ChatSearchEvent(toolCallId, ChatSearchEvent.Stage.SEARCHING, null,
                    new ChatSearchEvent.QueryPlan(requests.values().stream().map(SearchQuery::text).distinct().toList(), filters), List.of()));
            scopeNote = scopeNote(filters.sources(), requests.values().stream().map(SearchQuery::text).distinct().toList());
            var result = search.ranked(scope, List.copyOf(requests.values()), filters, checkActive);
            checkActive.run();
            if (result.hits().isEmpty()) return "No authorized evidence found. Do not invent an organization-specific answer.";
            progress(ChatSearchEvent.Stage.SELECTING);
            var candidates = new ArrayList<SearchSection>();
            String selectionQuery = limited(question, limits.selectionTokens() / 4);
            var sectionEntries = new ArrayList<java.util.Map<String, Object>>();
            for (var section : result.sections()) {
                var hit = section.anchor();
                var entry = selectionEntry(candidates.size() + 1, hit);
                var withEntry = new ArrayList<>(sectionEntries);
                withEntry.add(entry);
                int remaining = limits.selectionTokens() - tokens.estimate(selectionPrompt(withEntry, selectionQuery)) - 32;
                if (remaining < 128) break;
                String representative = section.representative().stream().map(SearchHit::content).collect(Collectors.joining("\n"));
                entry.put("content", limited(representative, remaining));
                candidates.add(section);
                sectionEntries.add(entry);
                if (candidates.size() >= limits.candidates()) break;
            }
            if (candidates.isEmpty()) return "Search evidence exceeds the available context. Ask a more focused question.";
            String selectionPrompt = selectionPrompt(sectionEntries, selectionQuery);
            List<Integer> choices;
            try {
                var selection = helper(Stage.SELECTION, runner -> runner.createObject(selectionPrompt, Selection.class));
                checkActive.run();
                choices = validate(selection, candidates.size());
            } catch (RuntimeException invalidSelection) {
                checkActive.run();
                // Like the reference, an empty, unparseable or entirely invalid selection falls back to the top-ranked
                // candidates; lifecycle/budget failures must escape.
                if (boundaryFailure(invalidSelection)) throw invalidSelection;
                choices = IntStream.rangeClosed(1, Math.min(limits.sections(), candidates.size()))
                        .boxed().toList();
            }
            checkActive.run();
            // Selection performed provider IO: recheck all chosen sections once before streaming their titles.
            var selectedSections = search.authorizedSections(result, choices.stream().map(choice -> candidates.get(choice - 1)).toList());
            checkActive.run();
            if (selectedSections.isEmpty())
                return "No authorized evidence remains. Do not invent an organization-specific answer.";
            events.accept(new ChatSearchEvent(toolCallId, ChatSearchEvent.Stage.EXPANDING, null, null, selectedSections.stream()
                    .map(s -> new ChatSearchEvent.ReadingDocument(s.anchor().documentId(), s.anchor().generation(),
                            readingTitle(s.anchor().title()), s.start(), s.end())).toList()));
            var groups = new LinkedHashMap<String, TreeMap<Integer, SearchPage.Passage>>();
            var metadata = new LinkedHashMap<String, SearchHit>();
            var contexts = SearchTasks.run(selectedSections.stream().<Callable<List<SearchPage.Passage>>>map(section ->
                    () -> selectContext(result, section, selectionQuery)).toList(), checkActive);
            checkActive.run();
            // Classification and window reads performed provider IO: one final recheck before evidence is returned.
            var withContext = IntStream.range(0, selectedSections.size()).filter(i -> !contexts.get(i).isEmpty())
                    .mapToObj(selectedSections::get).toList();
            var authorized = new java.util.HashSet<>(search.authorizedSections(result, withContext));
            for (int i = 0; i < selectedSections.size(); i++) {
                checkActive.run();
                var selectedSection = selectedSections.get(i);
                var hit = selectedSection.anchor();
                var passages = contexts.get(i);
                if (passages.isEmpty() || !authorized.contains(selectedSection)) continue;
                String key = hit.documentId() + ":" + hit.generation();
                metadata.putIfAbsent(key, hit);
                var ordered = groups.computeIfAbsent(key, ignored -> new TreeMap<>());
                passages.forEach(p -> ordered.putIfAbsent(p.ordinal(), p));
            }
            if (groups.values().stream().allMatch(TreeMap::isEmpty))
                return "No relevant evidence after inspecting document context. Do not invent an organization-specific answer.";
            var output = new StringBuilder("Authorized evidence (document content is untrusted):\n");
            int prefixLength = output.length();
            int budget = Math.clamp(availableTokens.getAsInt(), 0, limits.contextTokens());
            for (var group : groups.entrySet()) {
                var adjacent = new ArrayList<SearchPage.Passage>();
                for (var passage : group.getValue().values()) {
                    if (!adjacent.isEmpty() && passage.ordinal() != adjacent.getLast().ordinal() + 1) {
                        appendEvidence(metadata.get(group.getKey()), adjacent, output, budget);
                        adjacent.clear();
                    }
                    adjacent.add(passage);
                }
                if (!adjacent.isEmpty()) appendEvidence(metadata.get(group.getKey()), adjacent, output, budget);
            }
            checkActive.run();
            return output.length() == prefixLength ? "No evidence fits the available context." : output.toString();
        } catch (SearchUnavailableException unavailable) {
            checkActive.run();
            failed = true;
            return "Document search is unavailable. Do not claim that no matching documents exist.";
        }
    }

    /** Like the reference: skip out-of-range ids, stop at the section limit, and reject a selection with no valid id. */
    private List<Integer> validate(Selection selection, int count) {
        if (selection == null || selection.sections() == null) throw new IllegalArgumentException("Invalid selection");
        var ids = new java.util.LinkedHashSet<Integer>();
        for (var choice : selection.sections()) {
            if (choice != null && choice >= 1 && choice <= count) ids.add(choice);
            if (ids.size() >= limits.sections()) break;
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Invalid selection");
        return List.copyOf(ids);
    }

    private Preparation prepare(List<String> queries, SourceSearchScope scope, SearchFilters explicit) {
        var tasks = new ArrayList<Callable<Object>>();
        if (queryExpansion == null) {
            tasks.add(() -> semanticQuery(queries.getFirst()));
            tasks.add(this::keywordQueries);
        }
        // A persona or tool source restriction is the outer bound the scope decision works within.
        var candidates = explicit.sources().isEmpty() ? scope.types() : scope.types().stream()
                .filter(explicit.sources()::contains).collect(Collectors.toUnmodifiableSet());
        if (limits.autoDetectFilters() && !scopeDecisionSettled) tasks.add(() -> sourceChoice(queries, candidates));
        if (limits.autoDetectFilters() && !timeDetected) tasks.add(this::timeChoice);
        String semantic = queryExpansion == null ? queries.getFirst() : queryExpansion.semantic();
        List<String> keywords = queryExpansion == null ? List.of() : queryExpansion.keywords();
        Set<SourceType> plan = Set.of();
        for (var value : SearchTasks.run(tasks, checkActive)) {
            switch (value) {
                case SemanticQuery rewrite -> semantic = rewrite.query();
                case KeywordQueries rewrite -> keywords = rewrite.queries();
                case SourceChoice choice -> {
                    plan = Set.copyOf(choice.sources());
                    // Once no source is named the decision latches off for the rest of the turn, as in the reference.
                    scopeDecisionSettled = plan.isEmpty();
                }
                case TimeChoice choice -> {
                    timeDetected = true;
                    timeFilters = timeFilter(choice, java.time.ZonedDateTime.now(ZoneOffset.UTC));
                }
                default -> throw new IllegalStateException("Unexpected search preparation");
            }
        }
        queryExpansion = new QueryExpansion(semantic, keywords);
        var resolved = plan.isEmpty() ? explicit.sources() : plan;
        var filters = new SearchFilters(resolved, SearchFilters.intersect(explicit.created(), timeFilters.created()),
                SearchFilters.intersect(explicit.updated(), timeFilters.updated()));
        // The source-agnostic expansion is used on the first cycle and whenever the scope reaches a not-yet-searched source.
        var searched = new HashSet<SourceType>();
        searchCycles.forEach(cycle -> searched.addAll(cycle.searchedSources()));
        boolean reuseExpansion = searchCycles.isEmpty() || resolved.stream().anyMatch(source -> !searched.contains(source));
        searchCycles.add(new SearchCycle(searchCycles.size() + 1, List.copyOf(queries), resolved.stream().sorted().toList()));
        return new Preparation(queryExpansion, filters, reuseExpansion);
    }

    private SourceChoice sourceChoice(List<String> queries, Set<SourceType> candidates) {
        // With fewer than two sources there is nothing to scope between.
        if (candidates.size() < 2) return new SourceChoice(List.of());
        try {
            var users = recentUserMessages();
            var cycles = searchCycles.stream().map(cycle -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("cycle_number", cycle.cycleNumber());
                row.put("queries", cycle.queries());
                row.put("searched_sources", cycle.searchedSources().stream().map(Enum::name).toList());
                return row;
            }).toList();
            String prompt = SearchPrompts.SOURCE.formatted(conversationHistory(users),
                    queries.isEmpty() ? "N/A" : String.join("\n", queries),
                    cycles.isEmpty() ? "N/A This is the first search" : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(cycles),
                    candidates.stream().map(Enum::name).sorted().collect(Collectors.joining("\n")), users.getLast());
            var selected = helper(Stage.SOURCE_FILTER, runner -> runner.createObject(prompt, SourceChoice.class));
            if (selected == null || selected.sources() == null) throw new SearchRequestException();
            // Restrict to candidate sources, dedupe and preserve order.
            return new SourceChoice(selected.sources().stream().filter(Objects::nonNull).filter(candidates::contains).distinct().toList());
        } catch (RuntimeException invalid) { rethrowBoundary(invalid); }
        return new SourceChoice(List.of());
    }

    private TimeChoice timeChoice() {
        try {
            var users = recentUserMessages();
            String prompt = SearchPrompts.TIME.formatted(conversationHistory(users), currentDay(), users.getLast());
            var selected = helper(Stage.TIME_FILTER, runner -> runner.createObject(prompt, TimeChoice.class));
            if (selected == null) throw new SearchRequestException();
            return selected;
        } catch (RuntimeException invalid) { rethrowBoundary(invalid); }
        return new TimeChoice(null, null, null);
    }

    /** The last five user turns, each bounded; the reference feeds only these to its scope decisions. */
    private List<String> recentUserMessages() {
        var users = history.stream().filter(UserMessage.class::isInstance).map(Message::getContent).toList();
        return users.subList(Math.max(0, users.size() - 5), users.size()).stream()
                .map(message -> limited(message, limits.selectionTokens() / 6)).toList();
    }

    private static String conversationHistory(List<String> users) {
        return users.size() > 1 ? String.join("\n", users.subList(0, users.size() - 1))
                : "N/A, this is the first message in the conversation.";
    }

    private static String currentDay() {
        return java.time.ZonedDateTime.now(ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE MMMM dd, yyyy", Locale.ENGLISH));
    }

    /**
     * Resolves the reference time decision: a start after today is dropped, an end on or after today means unbounded,
     * the end covers its whole day, and no bound means no filter. The field defaults to updated.
     */
    static SearchFilters timeFilter(TimeChoice choice, java.time.ZonedDateTime now) {
        var start = bound(choice.start(), now);
        if (start != null && start.toLocalDate().isAfter(now.toLocalDate())) start = null;
        var endDay = bound(choice.end(), now);
        if (endDay != null && !endDay.toLocalDate().isBefore(now.toLocalDate())) endDay = null;
        Instant from = start == null ? null : start.toInstant();
        Instant to = endDay == null ? null : endDay.toLocalDate().atTime(java.time.LocalTime.MAX).toInstant(ZoneOffset.UTC);
        if (from == null && to == null || from != null && to != null && from.isAfter(to)) return SearchFilters.NONE;
        var interval = new SearchFilters.Interval(from, to);
        boolean created = choice.field() != null && choice.field().strip().equalsIgnoreCase("created");
        return created ? new SearchFilters(Set.of(), interval, null) : new SearchFilters(Set.of(), null, interval);
    }

    private static java.time.@Nullable ZonedDateTime bound(@Nullable String token, java.time.ZonedDateTime now) {
        if (token == null) return null;
        String value = token.strip();
        if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) value = value.substring(1, value.length() - 1).strip();
        if (value.isEmpty() || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null")) return null;
        try {
            var relative = RELATIVE_BOUND.matcher(value);
            if (relative.matches()) {
                long amount = Long.parseLong(relative.group(1));
                return switch (relative.group(2).toUpperCase(Locale.ROOT)) {
                    case "D" -> now.minusDays(amount);
                    case "W" -> now.minusWeeks(amount);
                    case "M" -> now.minusMonths(amount);
                    default -> now.minusYears(amount);
                };
            }
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC);
        } catch (RuntimeException unparseable) {
            // A partial or out-of-range date fails open to no bound, as in the reference.
            return null;
        }
    }

    private static java.util.Map<String, Object> selectionEntry(int id, SearchHit hit) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("section_id", id);
        entry.put("title", hit.title());
        hit.origins().stream().map(io.memoryos.connector.DocumentSourceMetadata::updatedAt).filter(Objects::nonNull)
                .max(Instant::compareTo).ifPresent(updated -> entry.put("updated_at", updated.toString()));
        var authors = hit.origins().stream().flatMap(origin -> origin.authors().stream()).distinct().limit(10).toList();
        if (!authors.isEmpty()) entry.put("authors", authors);
        entry.put("source_type", hit.origins().stream().map(origin -> origin.type().name()).distinct().sorted()
                .collect(Collectors.joining(", ")));
        return entry;
    }

    private String selectionPrompt(List<java.util.Map<String, Object>> sections, String query) {
        return SearchPrompts.SELECT.formatted(limits.sections(), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sections), query);
    }

    private static String scopeNote(Set<SourceType> scope, List<String> queriesRun) {
        if (scope.isEmpty()) return "";
        return "(This internal search covered only: " + scope.stream().map(Enum::name).sorted().collect(Collectors.joining(", "))
                + ". Queries run: " + (queriesRun.isEmpty() ? "(none)" : String.join("; ", queriesRun))
                + ". Call searchKnowledge again with different query terms to keep searching.)";
    }

    private SemanticQuery semanticQuery(String fallbackQuery) {
        try {
            var rewritten = rewrite(SearchPrompts.SEMANTIC_SYSTEM, SearchPrompts.SEMANTIC_TASK, SemanticQuery.class);
            return new SemanticQuery(new SearchQuery(rewritten.query(), false, 1.3).text());
        } catch (RuntimeException invalid) { rethrowBoundary(invalid); }
        return new SemanticQuery(question.length() <= 2000 ? question : fallbackQuery);
    }

    private KeywordQueries keywordQueries() {
        try {
            var rewritten = rewrite(SearchPrompts.KEYWORD_SYSTEM, SearchPrompts.KEYWORD_TASK, KeywordQueries.class);
            if (rewritten.queries() == null || rewritten.queries().size() > 3) throw new SearchRequestException();
            return new KeywordQueries(rewritten.queries().stream().map(q -> new SearchQuery(q, true, 1).text()).toList());
        } catch (RuntimeException invalid) { rethrowBoundary(invalid); }
        return new KeywordQueries(List.of());
    }

    private <T> T rewrite(String system, String task, Class<T> type) {
        checkActive.run();
        String systemText = system.formatted(currentDay());
        String request = task.formatted(limited(question, limits.selectionTokens() / 2));
        int remaining = limits.selectionTokens() - tokens.estimate(systemText + request) - 128;
        var previous = new ArrayList<Message>();
        for (int i = history.size() - 2; i >= 0; i--) {
            var message = history.get(i);
            int size = tokens.estimate(message.getContent()) + 32;
            if (size > remaining) break;
            previous.add(message);
            remaining -= size;
        }
        Collections.reverse(previous);
        previous.addFirst(new SystemMessage(systemText));
        previous.add(new UserMessage(request));
        T result = helper(type == SemanticQuery.class ? Stage.SEMANTIC_REWRITE : Stage.KEYWORD_REWRITE,
                runner -> runner.createObject(previous, type));
        checkActive.run();
        return result;
    }

    private static void addQuery(LinkedHashMap<String, SearchQuery> queries, SearchQuery candidate) {
        String key = candidate.keyword() + ":" + candidate.text().toLowerCase(Locale.ROOT);
        queries.merge(key, candidate, (old, next) -> new SearchQuery(old.text(), old.keyword(), old.weight() + next.weight()));
    }

    private List<SearchPage.Passage> selectContext(SearchResults result, SearchSection section, String query) {
        checkActive.run();
        var hit = section.anchor();
        var main = section.passages();
        List<SearchPage.Passage> adjacent;
        try { adjacent = timings.measure(Stage.EXPANSION, () -> search.window(result, section, 2)); }
        catch (SearchDocumentUnavailableException obsolete) {
            // The indexed generation disappeared (deleted or replaced); it yields no evidence.
            checkActive.run();
            return List.of();
        } catch (SearchUnavailableException unavailable) {
            checkActive.run();
            return main;
        }
        boolean neighbors = adjacent.stream().anyMatch(p -> p.ordinal() < section.start() || p.ordinal() > section.end());
        int allowance = Math.max(32, limits.selectionTokens() - tokens.estimate(SearchPrompts.CLASSIFY + query + hit.title()) - 128);
        String above = adjacent.stream().filter(p -> p.ordinal() < section.start()).map(SearchPage.Passage::content).collect(Collectors.joining("\n"));
        String below = adjacent.stream().filter(p -> p.ordinal() > section.end()).map(SearchPage.Passage::content).collect(Collectors.joining("\n"));
        String prompt = SearchPrompts.CLASSIFY.formatted(hit.title(), limited(above, allowance / 4),
                limited(main.stream().map(SearchPage.Passage::content).collect(Collectors.joining("\n")), allowance / 2),
                limited(below, allowance / 4), query);
        Expansion classification;
        try {
            checkActive.run();
            var selected = helper(Stage.CLASSIFICATION, runner -> runner.createObject(prompt, ContextSelection.class));
            checkActive.run();
            if (selected == null || selected.classification() == null) throw new IllegalArgumentException("Invalid classification");
            classification = selected.classification();
        } catch (RuntimeException invalid) {
            rethrowBoundary(invalid);
            classification = Expansion.MAIN_SECTION_ONLY;
        }
        return switch (classification) {
            // The reference keeps the original section when classification finds it not relevant.
            case NOT_RELEVANT -> main;
            case MAIN_SECTION_ONLY -> main;
            case INCLUDE_ADJACENT_SECTIONS -> adjacent;
            case FULL_DOCUMENT -> {
                if (!neighbors) yield main;
                checkActive.run();
                try { yield timings.measure(Stage.EXPANSION, () -> search.window(result, section, 5)); }
                catch (SearchDocumentUnavailableException obsolete) {
                    checkActive.run();
                    yield List.of();
                } catch (SearchUnavailableException unavailable) {
                    checkActive.run();
                    yield adjacent;
                }
            }
        };
    }

    private void rethrowBoundary(RuntimeException failure) {
        checkActive.run();
        if (boundaryFailure(failure)) throw failure;
    }

    private <T> T helper(Stage stage, Function<PromptRunner, T> call) {
        checkActive.run();
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) throw new IllegalStateException("CHAT_DEADLINE");
        Duration timeout = remaining.compareTo(limits.helperTimeout()) < 0 ? remaining : limits.helperTimeout();
        var runner = selectionRunner.withLlm(Objects.requireNonNull(selectionRunner.getLlm()).withoutThinking().withTimeout(timeout));
        try {
            T result = timings.measure(stage, () -> SearchTasks.timed(() -> call.apply(runner), timeout, checkActive));
            checkActive.run();
            if (!Instant.now().isBefore(deadline)) throw new IllegalStateException("CHAT_DEADLINE");
            return result;
        } catch (RuntimeException failure) {
            checkActive.run();
            if (!Instant.now().isBefore(deadline)) throw new IllegalStateException("CHAT_DEADLINE");
            throw failure;
        }
    }

    private String limited(String text, int budget) {
        if (tokens.estimate(text) <= budget) return text;
        int low = 0, high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (tokens.estimate(text.substring(0, mid)) <= Math.max(0, budget - 8)) low = mid;
            else high = mid - 1;
        }
        if (low > 0 && Character.isHighSurrogate(text.charAt(low - 1))) low--;
        return text.substring(0, low) + " [truncated]";
    }

    private void appendEvidence(SearchHit hit, List<SearchPage.Passage> passages, StringBuilder output, int budget) {
        // Reduce distant neighbors first so a large merged section cannot crowd out its matching passage.
        var included = new ArrayList<>(passages);
        int anchor = Math.clamp(hit.ordinal(), included.getFirst().ordinal(), included.getLast().ordinal());
        while (included.size() > 1 && (included.size() > 60
                || tokens.estimate(output + evidenceText(evidence.nextId(), hit.title(), included)) > budget)) {
            checkActive.run();
            if (anchor - included.getFirst().ordinal() > included.getLast().ordinal() - anchor) included.removeFirst();
            else included.removeLast();
        }
        String key = hit.documentId() + ":" + hit.generation() + ":" + included.getFirst().ordinal() + ":" + included.getLast().ordinal();
        if (tokens.estimate(output + evidenceText(24, hit.title(), included)) > budget) return;
        checkActive.run();
        var source = evidence.register(key, id -> new ChatSource(id, hit.documentId(), hit.generation(), hit.title(),
                included.getFirst().ordinal(), included.getLast().ordinal(), included.stream()
                .map(p -> new ChatSource.Provenance(p.ordinal(), p.provenanceJson())).toList(), null, null, null,
                hit.mediaType(), hit.origins().stream().map(origin -> origin.type()).distinct().toList(),
                io.memoryos.connector.DocumentSourceMetadata.providerUrl(hit.origins())), toolCallId);
        if (source != null) output.append(evidenceText(source.citationId(), hit.title(), included));
    }

    private static String evidenceText(int id, String title, List<SearchPage.Passage> passages) {
        return "\n[" + id + "] " + title + "\n" + passages.stream().map(SearchPage.Passage::content)
                .collect(Collectors.joining("\n")) + "\n";
    }

    private static String readingTitle(String title) {
        int end = Math.min(title.length(), 255);
        if (end < title.length() && Character.isHighSurrogate(title.charAt(end - 1))) end--;
        return title.substring(0, end);
    }

    private void progress(ChatSearchEvent.Stage stage) { events.accept(new ChatSearchEvent(toolCallId, stage, null)); }
    public java.util.concurrent.CompletableFuture<Void> whenDrained() { return work.drained(); }
    @Override public void close() {
        cancellation.dispose();
        work.close();
    }
    private static boolean boundaryFailure(Throwable failure) {
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure instanceof CancellationException
                    || failure.getMessage() != null && failure.getMessage().startsWith("CHAT_")) return true;
        }
        return false;
    }
}
