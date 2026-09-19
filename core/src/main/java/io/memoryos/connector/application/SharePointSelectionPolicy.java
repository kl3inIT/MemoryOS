package io.memoryos.connector.application;

import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointGlob;
import io.memoryos.connector.SharePointSourceService.Scope;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SharePointSourceService.SelectionPolicy;
import io.memoryos.connector.SharePointUrl;
import io.memoryos.connector.SourceException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deployment bounds for a SharePoint scope request, and the checks that need no call to Microsoft. */
@Component
public final class SharePointSelectionPolicy {
    public static final int ABSOLUTE_REQUEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_INTERVAL_MINUTES = Integer.MAX_VALUE;
    private static final int MAX_PRUNE_HOURS = 8760;

    private final SelectionPolicy value;

    public SharePointSelectionPolicy(
            @Value("${memoryos.sharepoint.selection.max-roots-per-source:1000}") int maxRoots,
            @Value("${memoryos.sharepoint.selection.max-request-bytes:3145728}") int maxBytes) {
        if (maxRoots < 1 || maxRoots > 10_000 || maxBytes < 1024 || maxBytes > ABSOLUTE_REQUEST_BYTES) {
            throw new IllegalArgumentException("SharePoint selection policy exceeds finite deployment bounds");
        }
        value = new SelectionPolicy(maxRoots, SharePointGlob.MAX_PATTERNS, maxBytes);
    }

    public SelectionPolicy value() { return value; }

    /**
     * Parses and checks a scope without calling Microsoft: at least one kind of content, addresses on one
     * host, no root inside another, and exclusions that compile.
     */
    public List<SharePointUrl> requireScope(Scope scope) {
        if (!scope.includeDocuments() && !scope.includePages()) {
            throw SourceException.invalid("Select document libraries, site pages, or both.",
                    "SharePoint source selects neither documents nor pages");
        }
        if (scope.syncIntervalMinutes() < 1 || scope.syncIntervalMinutes() > MAX_INTERVAL_MINUTES
                || scope.pruneIntervalHours() < 0 || scope.pruneIntervalHours() > MAX_PRUNE_HOURS) {
            throw SourceException.invalid("Choose a synchronization interval of at least one minute and a"
                    + " prune interval of at most one year.", "SharePoint schedule outside deployment bounds");
        }
        SharePointGlob.all(scope.excludedSites());
        SharePointGlob.all(scope.excludedPaths());
        if (scope.scopeMode() == ScopeMode.ALL_SITES) {
            if (!scope.siteUrls().isEmpty()) {
                throw SourceException.invalid("Remove the addresses, or choose specific sites.",
                        "SharePoint ALL_SITES scope carries addresses");
            }
            return List.of();
        }
        if (scope.siteUrls().isEmpty()) {
            throw SourceException.invalid("Add at least one site, library or folder address.",
                    "SharePoint SPECIFIC scope carries no address");
        }
        if (scope.siteUrls().size() > value.maxRootsPerSource()) {
            throw SourceException.invalid("That is more addresses than this deployment allows.",
                    "SharePoint roots exceed the configured maximum");
        }
        var roots = new ArrayList<SharePointUrl>(scope.siteUrls().size());
        for (String url : scope.siteUrls()) roots.add(SharePointUrl.parse(url));
        for (SharePointUrl root : roots) {
            if (!root.host().equals(roots.getFirst().host())) throw SharePointException.mixedTenants();
        }
        for (int outer = 0; outer < roots.size(); outer++) {
            for (int inner = 0; inner < roots.size(); inner++) {
                if (outer != inner && roots.get(outer).covers(roots.get(inner))) throw SharePointException.overlappingRoots();
            }
        }
        return List.copyOf(roots);
    }

    public void requireSize(String name, Scope scope) {
        long bytes = name.getBytes(StandardCharsets.UTF_8).length + 512L;
        for (String url : scope.siteUrls()) bytes += url.getBytes(StandardCharsets.UTF_8).length + 4L;
        for (String pattern : scope.excludedSites()) bytes += pattern.getBytes(StandardCharsets.UTF_8).length + 4L;
        for (String pattern : scope.excludedPaths()) bytes += pattern.getBytes(StandardCharsets.UTF_8).length + 4L;
        if (bytes > value.maxRequestBytes()) {
            throw SourceException.invalid("The scope request exceeds the configured byte limit.",
                    "SharePoint scope byte budget exceeded");
        }
    }
}
