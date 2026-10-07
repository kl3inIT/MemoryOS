package io.memoryos.chat.tools;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.rag.model.Chunk;
import com.embabel.agent.rag.model.ContentElement;
import com.embabel.agent.rag.model.Retrievable;
import com.embabel.agent.rag.service.ResultExpander;
import com.embabel.agent.rag.service.TextQueryMode;
import com.embabel.agent.rag.service.TextSearch;
import com.embabel.agent.rag.service.VectorSearch;
import com.embabel.agent.rag.tools.ToolishRag;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.common.core.types.TextSimilaritySearchRequest;
import io.memoryos.chat.ChatEvidence;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchDocumentUnavailableException;
import io.memoryos.retrieval.SearchFilters;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchQuery;
import io.memoryos.retrieval.SearchRequestException;
import io.memoryos.retrieval.SearchResults;
import io.memoryos.retrieval.SearchSection;
import io.memoryos.retrieval.SearchUnavailableException;
import io.memoryos.shared.ActorId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * MEM-230 experiment E1, never merged: Embabel's native agentic RAG ({@link ToolishRag}) in place of
 * {@code search_knowledge}, backed by MemoryOS retrieval. One per turn. Every chunk the model sees passed the same
 * scope, allowlist and final {@code authorizedSections} recheck as {@link SearchTool}, and is registered as turn
 * evidence so its {@code [n]} is citable. Expansion only accepts chunk ids this instance returned.
 */
public final class P8EmbabelRag implements VectorSearch, TextSearch, ResultExpander {
    public static final boolean ENABLED = "embabel".equals(System.getenv("MEMORYOS_P8_SELECT"));
    /** The tool a grounded turn must call first. */
    public static final String FIRST_TOOL = "vectorSearch";

    private record Read(SearchResults results, SearchSection section) {}

    private final DocumentSearchService search;
    private final ActorId actor;
    private final @Nullable Set<UUID> allowedSourceIds;
    private final SearchFilters filters;
    private final ChatEvidence evidence;
    private final ChatToolActivity activity;
    private final Consumer<ChatToolEvent> events;
    private final Runnable checkActive;
    private final IntSupplier availableTokens;
    private final TokenCountEstimator tokens;
    private final Map<String, Read> reads = new ConcurrentHashMap<>();

    public P8EmbabelRag(DocumentSearchService search, ActorId actor, @Nullable Collection<UUID> allowedSourceIds,
                        @Nullable Instant knowledgeCutoff, ChatEvidence evidence, ChatToolActivity activity,
                        Consumer<ChatToolEvent> events, Runnable checkActive, IntSupplier availableTokens,
                        TokenCountEstimator tokens) {
        this.search = search;
        this.actor = actor;
        this.allowedSourceIds = allowedSourceIds == null ? null : Set.copyOf(allowedSourceIds);
        this.filters = new SearchFilters(Set.of(), null,
                knowledgeCutoff == null ? null : new SearchFilters.Interval(knowledgeCutoff, null));
        this.evidence = evidence;
        this.activity = activity;
        this.events = event -> { checkActive.run(); events.accept(event); };
        this.checkActive = checkActive;
        this.availableTokens = availableTokens;
        this.tokens = tokens;
    }

    /** ToolishRag's tools without the reference prefix: vectorSearch, textSearch, broadenChunk, zoomOut. */
    public List<Tool> tools() {
        var rag = new ToolishRag("knowledge", "Authorized organization documents", this);
        String prefix = rag.toolPrefix() + "_";
        return rag.tools().stream().map(tool -> {
            String name = tool.getDefinition().getName();
            return name.startsWith(prefix) ? tool.withName(name.substring(prefix.length())) : tool;
        }).toList();
    }

    @Override public boolean supportsType(String type) { return "Chunk".equals(type); }

    @Override
    public <T extends Retrievable> List<SimilarityResult<T>> vectorSearch(TextSimilaritySearchRequest request, Class<T> clazz) {
        return search(request, clazz, false, "vectorSearch");
    }

    @Override
    public <T extends Retrievable> List<SimilarityResult<T>> textSearch(TextSimilaritySearchRequest request, Class<T> clazz) {
        return search(request, clazz, true, "textSearch");
    }

    @Override public Set<TextQueryMode> getSupportedQueryModes() { return Set.of(TextQueryMode.LITERAL); }
    @Override public TextQueryMode getQueryMode() { return TextQueryMode.LITERAL; }
    @Override public String getLuceneSyntaxNotes() { return "Plain keywords matched with BM25 over titles and content; no operators."; }

    @Override
    public List<ContentElement> expandResult(String id, Method method, int elementsToAdd) {
        checkActive.run();
        var read = reads.get(id);
        if (read == null) return List.of();
        int neighbors = method == Method.ZOOM_OUT ? 5 : Math.clamp(elementsToAdd, 1, 5);
        List<SearchPage.Passage> passages;
        try {
            passages = search.window(read.results(), read.section(), neighbors);
        } catch (SearchDocumentUnavailableException | SearchUnavailableException unavailable) {
            checkActive.run();
            return List.of();
        }
        checkActive.run();
        // The window read performed IO: recheck before any passage leaves.
        if (search.authorizedSections(read.results(), List.of(read.section())).isEmpty() || passages.isEmpty()) return List.of();
        var call = call(method == Method.ZOOM_OUT ? "zoomOut" : "broadenChunk");
        var chunk = chunk(read, passages, call, Math.max(0, availableTokens.getAsInt()));
        if (chunk == null) return List.of();
        reading(call, List.of(new ChatToolEvent.ReadingDocument(read.section().anchor().documentId(),
                read.section().anchor().generation(), title(read.section().anchor().title()),
                passages.getFirst().ordinal(), passages.getLast().ordinal())));
        return List.of(chunk);
    }

    @SuppressWarnings("unchecked")
    private <T extends Retrievable> List<SimilarityResult<T>> search(TextSimilaritySearchRequest request, Class<T> clazz,
                                                                   boolean keyword, String tool) {
        checkActive.run();
        if (!clazz.isAssignableFrom(Chunk.class)) return List.of();
        SearchQuery query;
        try { query = new SearchQuery(request.getQuery(), keyword, 1); }
        catch (SearchRequestException invalid) { return List.of(); }
        var call = call(tool);
        try {
            var scope = search.scope(actor);
            if (allowedSourceIds != null) scope = new SourceSearchScope(scope.tenant(), scope.actor(), scope.sources().entrySet().stream()
                    .filter(entry -> allowedSourceIds.contains(entry.getKey()))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)), scope.accessTokens());
            events.accept(ChatToolEvent.searching(call, new ChatToolEvent.QueryPlan(List.of(query.text()), filters)));
            var results = search.ranked(scope, List.of(query), filters, checkActive);
            checkActive.run();
            var sections = results.sections().stream().limit(Math.clamp(request.getTopK(), 1, 20)).toList();
            // Final authority recheck after the last IO, as SearchTool does before returning evidence.
            var authorized = search.authorizedSections(results, sections);
            checkActive.run();
            int budget = Math.max(0, availableTokens.getAsInt());
            var out = new ArrayList<SimilarityResult<Chunk>>();
            var documents = new ArrayList<ChatToolEvent.ReadingDocument>();
            for (var section : authorized) {
                var read = new Read(results, section);
                var chunk = chunk(read, section.passages(), call, budget);
                if (chunk == null) break;
                budget -= tokens.estimate(chunk.getText());
                out.add(SimilarityResult.create(chunk, 1.0 / (1 + out.size())));
                var hit = section.anchor();
                documents.add(new ChatToolEvent.ReadingDocument(hit.documentId(), hit.generation(), title(hit.title()),
                        section.start(), section.end()));
            }
            reading(call, documents);
            return (List<SimilarityResult<T>>) (List<?>) out;
        } catch (SearchUnavailableException | SearchDocumentUnavailableException unavailable) {
            checkActive.run();
            activity.fail();
            return List.of();
        }
    }

    /** Registers the passages as citable evidence and renders them as SearchTool does: "[n] title\npassages". */
    private @Nullable Chunk chunk(Read read, List<SearchPage.Passage> passages, ChatToolEvent.Call call, int budget) {
        var hit = read.section().anchor();
        int start = passages.getFirst().ordinal(), end = passages.getLast().ordinal();
        String body = passages.stream().map(SearchPage.Passage::content).collect(Collectors.joining("\n"));
        if (tokens.estimate(body) + 32 > budget) return null;
        String key = hit.documentId() + ":" + hit.generation() + ":" + start + ":" + end;
        var source = evidence.register(key, number -> ChatSource.document(number, hit.documentId(), hit.generation(), hit.title(),
                start, end, passages.stream().map(p -> new ChatSource.Provenance(p.ordinal(), p.provenanceJson())).toList())
                .described(hit.mediaType(), hit.origins().stream().map(DocumentSourceMetadata::type).distinct().toList(),
                        DocumentSourceMetadata.providerUrl(hit.origins())), call);
        if (source == null) return null;
        String id = key;
        reads.put(id, read);
        return Chunk.Companion.create("[" + source.citationId() + "] " + hit.title() + "\n" + body, hit.documentId().toString(),
                Map.of(), id);
    }

    private void reading(ChatToolEvent.Call call, List<ChatToolEvent.ReadingDocument> documents) {
        if (!documents.isEmpty()) events.accept(ChatToolEvent.reading(call, documents));
    }

    private ChatToolEvent.Call call(String tool) {
        var current = activity.current();
        return current == null ? new ChatToolEvent.Call("search", tool) : current;
    }

    private static String title(String title) {
        int end = Math.min(title.length(), 255);
        if (end < title.length() && Character.isHighSurrogate(title.charAt(end - 1))) end--;
        return title.substring(0, end);
    }
}
