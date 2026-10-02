package io.memoryos.api.mcp.endpoint;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.chat.DocumentSetService;
import io.memoryos.connector.SourceType;
import io.memoryos.mcp.McpEndpointProperties;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchDocument;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchRequest;
import io.memoryos.shared.ActorId;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * MEM-114: the read-only tools of the MemoryOS MCP endpoint. {@code search} and {@code fetch} keep the shapes ChatGPT's
 * deep research and company knowledge require, a single query string and an id, so the Search page's filters live in
 * {@code search_with_filters}. All read through the Search page's services as the person whose token the request
 * carries, and nothing is generated here: the client writes the answer from the evidence.
 *
 * <p>Spring AI runs a SYNC stateless tool on the request thread, so the security context of the call is the caller's.
 */
@Component
public class McpEndpointTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpEndpointTools.class);
    static final int SEARCH_RESULTS = 10;
    static final int RESULT_TEXT_CHARS = 2_000;
    /** Below the roughly 150,000 characters a Claude connector accepts for one result. */
    static final int FETCH_TEXT_CHARS = 100_000;
    private static final int LAST_ORDINAL = 9_999;
    static final String INVALID_QUERY = "The query must be 1 to 1,000 characters. Shorten or rephrase it and search again.";
    static final String INVALID_ID = "Pass the id of a search result exactly as search returned it.";
    static final String INVALID_WINDOW = "updated_after must be on or before updated_before.";
    static final String TOO_MANY_DOCUMENT_SETS = "Pass at most 10 document set names.";
    static final String NO_DOCUMENT_SETS = "This person has no document sets; search without document_set_names.";
    private static final int DOCUMENT_SET_PAGE = 100;
    private static final int DOCUMENT_SET_PAGES = 10;
    private static final int LISTED_DOCUMENT_SETS = 30;

    private final DocumentSearchService documents;
    private final DocumentSetService documentSets;
    private final McpEndpointProperties endpoint;
    private final MeterRegistry metrics;

    McpEndpointTools(DocumentSearchService documents, DocumentSetService documentSets, McpEndpointProperties endpoint,
                     MeterRegistry metrics) {
        this.documents = documents;
        this.documentSets = documentSets;
        this.endpoint = endpoint;
        this.metrics = metrics;
    }

    @McpTool(name = "search", title = "Search organization knowledge", generateOutputSchema = true,
            description = """
                    Searches the organization's documents in MemoryOS that the signed-in person may read: contracts, \
                    policies, reports, meeting minutes and files from Google Drive, SharePoint and uploads. Use it for \
                    anything that is not public knowledge and is specific to the person, their team, their work or \
                    their organization. When the person limits the search to a source, a document set, a period or a \
                    file type, use search_with_filters instead.

                    Returns {"results": [{id, title, url, sourceNumber, text, updatedAt, sources}]}, at most 10, \
                    best first. text holds the passages that matched, cut at 2,000 characters. Cite each claim with \
                    the result's sourceNumber as [n]. Call fetch with a result's id to read the whole document when \
                    the passages are not enough. An empty list means nothing the person can read matched; say so \
                    rather than guessing.

                    Example: {"query": "annual leave policy for new employees"}""",
            annotations = @McpTool.McpAnnotations(title = "Search organization knowledge", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchResults search(@McpToolParam(required = true, description = "What to look for, in the person's own "
            + "words and language, 1 to 1,000 characters") String query) {
        return timed("search", INVALID_QUERY, () -> results(actor(),
                new SearchRequest(query, List.of(), null, null, 0, SEARCH_RESULTS, List.of(), List.of())));
    }

    // Spring AI names each input after the Java parameter, so these parameters carry the tool's snake_case names.
    @McpTool(name = "search_with_filters", title = "Search organization knowledge with filters",
            generateOutputSchema = true,
            description = """
                    Use this instead of search when the person limits the search to where documents come from \
                    (Google Drive, SharePoint or files uploaded to MemoryOS), to their document sets, to a period, \
                    or to a file type. Pass only the filters the person asked for; never add one to narrow results \
                    yourself. A filter left out keeps everything.

                    Returns the same {"results": [...]} as search, at most 10, best first. Cite each claim with the \
                    result's sourceNumber as [n], and call fetch with a result's id to read the whole document. An \
                    empty list means nothing the person can read matched these filters; say which filters were used.

                    Example: {"query": "quarterly revenue", "source_types": ["SHAREPOINT"], \
                    "updated_after": "2026-07-01", "file_types": ["PDF"]}""",
            annotations = @McpTool.McpAnnotations(title = "Search organization knowledge with filters",
                    readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchResults searchWithFilters(
            @McpToolParam(required = true, description = "What to look for, in the person's own words and language, "
                    + "1 to 1,000 characters") String query,
            @McpToolParam(required = false, description = "Where the documents come from. FILE means files uploaded "
                    + "to MemoryOS.") @Nullable List<SourceType> source_types,
            @McpToolParam(required = false, description = "Names of the person's document sets, as they call them, "
                    + "up to 10.") @Nullable List<String> document_set_names,
            @McpToolParam(required = false, description = "Only documents changed on or after this date, as "
                    + "YYYY-MM-DD in UTC.") @Nullable String updated_after,
            @McpToolParam(required = false, description = "Only documents changed on or before this date, as "
                    + "YYYY-MM-DD in UTC.") @Nullable String updated_before,
            @McpToolParam(required = false, description = "File types. SPREADSHEET covers Excel, Google Sheets and "
                    + "CSV.") @Nullable List<FileType> file_types) {
        return timed("search_with_filters", INVALID_QUERY, () -> {
            var actor = actor();
            Instant from = day(updated_after, "updated_after", LocalTime.MIN);
            Instant to = day(updated_before, "updated_before", LocalTime.MAX);
            if (from != null && to != null && from.isAfter(to)) throw new ToolFailure(INVALID_WINDOW);
            return results(actor, new SearchRequest(query, mediaTypes(file_types), from, to, 0, SEARCH_RESULTS,
                    source_types == null ? List.of() : source_types, documentSetIds(actor, document_set_names)));
        });
    }

    private SearchResults results(ActorId actor, SearchRequest request) {
        var page = documents.search(actor, request);
        var results = new ArrayList<SearchResult>(page.results().size());
        for (var result : page.results()) {
            results.add(new SearchResult(result.documentId().toString(), result.title(),
                    url(result.documentId(), result.providerUrl()), results.size() + 1,
                    excerpt(result.sections()), result.updatedAt().toString(),
                    result.sourceTypes().stream().map(Enum::name).toList()));
        }
        return new SearchResults(results);
    }

    /** A UTC day as its first or last instant, as Chat's search tool bounds a window. */
    private static @Nullable Instant day(@Nullable String value, String name, LocalTime time) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.strip()).atTime(time).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException invalid) {
            throw new ToolFailure("Pass " + name + " as a date such as 2026-03-01.");
        }
    }

    private static List<String> mediaTypes(@Nullable List<FileType> fileTypes) {
        if (fileTypes == null) return List.of();
        var types = new LinkedHashSet<String>();
        for (var fileType : fileTypes) if (fileType != null) types.addAll(fileType.mediaTypes);
        return List.copyOf(types);
    }

    /**
     * The person's document sets by name, case aside. Like Onyx, an unknown name is answered with the names the
     * person can use, so the client can ask again without a listing tool. Search re-checks each set's share.
     */
    private List<UUID> documentSetIds(ActorId actor, @Nullable List<String> names) {
        if (names == null) return List.of();
        var wanted = new LinkedHashSet<String>();
        for (String name : names) if (name != null && !name.isBlank()) wanted.add(name.strip());
        if (wanted.isEmpty()) return List.of();
        if (wanted.size() > 10) throw new ToolFailure(TOO_MANY_DOCUMENT_SETS);
        var byName = new HashMap<String, UUID>();
        var listed = new ArrayList<String>();
        for (int page = 0; page < DOCUMENT_SET_PAGES; page++) {
            var sets = documentSets.list(actor, page * DOCUMENT_SET_PAGE, DOCUMENT_SET_PAGE);
            for (var set : sets) {
                byName.putIfAbsent(set.name().strip().toLowerCase(Locale.ROOT), set.id());
                listed.add(set.name().strip());
            }
            if (sets.size() < DOCUMENT_SET_PAGE) break;
        }
        if (listed.isEmpty()) throw new ToolFailure(NO_DOCUMENT_SETS);
        var ids = new ArrayList<UUID>(wanted.size());
        for (String name : wanted) {
            UUID id = byName.get(name.toLowerCase(Locale.ROOT));
            if (id == null) {
                var shown = listed.stream().limit(LISTED_DOCUMENT_SETS).toList();
                throw new ToolFailure("Document set \"" + name + "\" not found. Available: " + String.join(", ", shown)
                        + (listed.size() > shown.size() ? ", and " + (listed.size() - shown.size()) + " more." : "."));
            }
            ids.add(id);
        }
        return ids;
    }

    /** The Search page's file types, plus spreadsheets, as the media types Documents record. */
    public enum FileType {
        PDF("application/pdf"),
        WORD("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword",
                "application/vnd.google-apps.document"),
        POWERPOINT("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.ms-powerpoint", "application/vnd.google-apps.presentation"),
        SPREADSHEET("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
                "application/vnd.google-apps.spreadsheet", "text/csv"),
        TEXT("text/plain"),
        MARKDOWN("text/markdown", "text/x-markdown");

        private final List<String> mediaTypes;

        FileType(String... mediaTypes) {
            this.mediaTypes = List.of(mediaTypes);
        }
    }

    @McpTool(name = "fetch", title = "Read a document", generateOutputSchema = true,
            description = """
                    Reads the full text of one document found by search, by the result's id, if the signed-in person \
                    may still read it. Use it when the passages search returned do not answer the question.

                    Returns {id, title, text, url, metadata}. A document longer than 100,000 characters is cut and \
                    the text ends by saying how many of its passages were shown.

                    Example: {"id": "<the id of a search result>"}""",
            annotations = @McpTool.McpAnnotations(title = "Read a document", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public FetchedDocument fetch(@McpToolParam(required = true, description = "The id of a search result, exactly as "
            + "search returned it") String id) {
        return timed("fetch", INVALID_ID, () -> {
            var actor = actor();
            var documentId = documentId(id);
            var text = new StringBuilder();
            SearchDocument page = documents.currentDocument(actor, documentId, 0);
            String title = page.title();
            int read = 0;
            boolean cut = false;
            while (true) {
                for (var passage : page.passages()) {
                    if (text.length() + passage.content().length() > FETCH_TEXT_CHARS) {
                        cut = true;
                        break;
                    }
                    if (!text.isEmpty()) text.append("\n\n");
                    text.append(passage.content());
                    read++;
                }
                int next = page.passages().isEmpty() ? LAST_ORDINAL + 1 : page.passages().getLast().ordinal() + 1;
                if (cut || !page.hasMore() || next > LAST_ORDINAL) break;
                page = documents.currentDocument(actor, documentId, next);
            }
            if (cut || page.hasMore()) {
                text.append("\n\n[Cut here: ").append(read).append(" of ").append(page.totalChunks())
                        .append(" passages shown.]");
            }
            var link = url(documentId, documents.providerUrl(actor, documentId).orElse(null));
            return new FetchedDocument(documentId.toString(), title, text.toString(), link,
                    Map.of("passages", String.valueOf(page.totalChunks())));
        });
    }

    /** A Drive or SharePoint Document opens in its provider; any other opens in MemoryOS's own document view. */
    private String url(UUID documentId, @Nullable String providerUrl) {
        if (providerUrl != null) return providerUrl;
        URI origin = endpoint.origin().orElseThrow(() -> new IllegalStateException("MCP endpoint URL is not configured"));
        return origin.resolve("/search?doc=" + documentId).toString();
    }

    private static String excerpt(List<SearchPage.Section> sections) {
        var text = new StringBuilder();
        for (var section : sections) {
            if (!text.isEmpty()) text.append("\n…\n");
            text.append(section.content());
            if (text.length() >= RESULT_TEXT_CHARS) break;
        }
        return text.length() <= RESULT_TEXT_CHARS ? text.toString() : text.substring(0, RESULT_TEXT_CHARS) + "…";
    }

    private static UUID documentId(String id) {
        try {
            return UUID.fromString(id.strip());
        } catch (IllegalArgumentException invalid) {
            throw new ToolFailure(INVALID_ID);
        }
    }

    private static ActorId actor() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof ActorAuthenticationToken token) {
            return token.getPrincipal().actorId();
        }
        // The security chain admits no call without one; reaching here means the tool runs off the request thread.
        throw new IllegalStateException("MCP tool called without the caller's identity");
    }

    /**
     * Records the call and turns every failure into a fixed message without a cause. Spring AI returns an exception's
     * message, and its causes' messages, to the client, so nothing internal may travel in one. A refused call says
     * what to change, as Onyx's tools do, so the client can call again correctly instead of giving up.
     */
    private <T> T timed(String tool, String invalid, Supplier<T> call) {
        long started = System.nanoTime();
        String outcome = "failed";
        try {
            T result = call.get();
            outcome = "success";
            return result;
        } catch (ToolFailure refused) {
            outcome = "refused";
            throw refused;
        } catch (BusinessException refused) {
            outcome = "refused";
            LOGGER.atInfo().addKeyValue("event", "mcp_endpoint.tool.refused").addKeyValue("tool", tool)
                    .addKeyValue("error_type", refused.getClass().getName()).addKeyValue("error_code", refused.code())
                    .log("MCP endpoint tool call refused");
            throw new ToolFailure(refused.category() == FailureCategory.VALIDATION ? invalid : ToolFailure.UNAVAILABLE);
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("event", "mcp_endpoint.tool.failed").addKeyValue("tool", tool)
                    .addKeyValue("error_type", failure.getClass().getName()).setCause(failure)
                    .log("MCP endpoint tool call failed");
            throw new ToolFailure(ToolFailure.FAILED);
        } finally {
            metrics.timer("memoryos.mcp.endpoint.call", "tool", tool, "outcome", outcome)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** A tool failure the client may see: a fixed sentence, no cause and no stack. */
    static final class ToolFailure extends RuntimeException {
        static final String UNAVAILABLE = "Not found in the documents this person can access. Search again rather "
                + "than guessing.";
        static final String FAILED = "MemoryOS could not complete the request. Try again shortly.";

        ToolFailure(String message) {
            super(message, null, false, false);
        }
    }

    public record SearchResults(List<SearchResult> results) {}

    /**
     * {@code sourceNumber} counts from 1 in rank order; {@code text} is the matching passages; {@code sources} names
     * where the document comes from (GOOGLE_DRIVE, SHAREPOINT, FILE), as far as the person may see.
     */
    public record SearchResult(String id, String title, String url, int sourceNumber, String text, String updatedAt,
                               List<String> sources) {}

    public record FetchedDocument(String id, String title, String text, String url, Map<String, String> metadata) {}
}
