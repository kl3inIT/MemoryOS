package io.memoryos.api.mcp.endpoint;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.mcp.McpEndpointProperties;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchDocument;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchRequest;
import io.memoryos.shared.ActorId;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
 * MEM-114: the two read-only tools of the MemoryOS MCP endpoint, in the {@code search} and {@code fetch} shapes ChatGPT's
 * deep research and company knowledge require. Both read through the Search page's services as the person whose token
 * the request carries, and nothing is generated here: the client writes the answer from the evidence.
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

    private final DocumentSearchService documents;
    private final McpEndpointProperties endpoint;
    private final MeterRegistry metrics;

    McpEndpointTools(DocumentSearchService documents, McpEndpointProperties endpoint, MeterRegistry metrics) {
        this.documents = documents;
        this.endpoint = endpoint;
        this.metrics = metrics;
    }

    @McpTool(name = "search", title = "Search organization knowledge", generateOutputSchema = true,
            description = "Searches the organization's documents the signed-in person may read and returns up to "
                    + SEARCH_RESULTS + " ranked results with matching text. Cite each claim with the result's "
                    + "sourceNumber as [n]. Use fetch with a result's id to read the whole document.",
            annotations = @McpTool.McpAnnotations(title = "Search organization knowledge", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchResults search(@McpToolParam(description = "What to look for, in the person's own words", required = true)
                                String query) {
        return timed("search", () -> {
            var page = documents.search(actor(),
                    new SearchRequest(query, List.of(), null, 0, SEARCH_RESULTS, List.of(), List.of()));
            var results = new ArrayList<SearchResult>(page.results().size());
            for (var result : page.results()) {
                results.add(new SearchResult(result.documentId().toString(), result.title(),
                        url(result.documentId(), result.providerUrl()), results.size() + 1,
                        excerpt(result.sections()), result.updatedAt().toString()));
            }
            return new SearchResults(results);
        });
    }

    @McpTool(name = "fetch", title = "Read a document", generateOutputSchema = true,
            description = "Reads the full text of one document returned by search, by its id, if the signed-in person "
                    + "may still read it. Very long documents are cut and say so.",
            annotations = @McpTool.McpAnnotations(title = "Read a document", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public FetchedDocument fetch(@McpToolParam(description = "The id of a search result", required = true) String id) {
        return timed("fetch", () -> {
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
            throw new ToolFailure(ToolFailure.UNAVAILABLE);
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
     * message, and its causes' messages, to the client, so nothing internal may travel in one.
     */
    private <T> T timed(String tool, Supplier<T> call) {
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
            throw new ToolFailure(refused.category() == FailureCategory.VALIDATION ? ToolFailure.INVALID : ToolFailure.UNAVAILABLE);
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
        static final String INVALID = "The request is not valid. Check the query or the id and try again.";
        static final String UNAVAILABLE = "Not found in the documents this person can access.";
        static final String FAILED = "MemoryOS could not complete the request. Try again shortly.";

        ToolFailure(String message) {
            super(message, null, false, false);
        }
    }

    public record SearchResults(List<SearchResult> results) {}

    /** {@code sourceNumber} counts from 1 in rank order; {@code text} is the matching passages. */
    public record SearchResult(String id, String title, String url, int sourceNumber, String text, String updatedAt) {}

    public record FetchedDocument(String id, String title, String text, String url, Map<String, String> metadata) {}
}
