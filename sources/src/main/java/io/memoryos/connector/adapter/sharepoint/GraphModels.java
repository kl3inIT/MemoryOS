package io.memoryos.connector.adapter.sharepoint;

import static io.memoryos.connector.SharePointProviderException.Failure.LIMIT_EXCEEDED;
import static io.memoryos.connector.SharePointProviderException.Failure.MALFORMED;

import com.microsoft.graph.models.BaseSitePage;
import com.microsoft.graph.models.Drive;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.Site;
import com.microsoft.graph.models.SitePage;
import com.microsoft.graph.models.StandardWebPart;
import com.microsoft.graph.models.TextWebPart;
import com.microsoft.graph.models.WebPart;
import com.microsoft.kiota.serialization.UntypedString;
import io.memoryos.connector.SharePointGateway;
import io.memoryos.connector.SharePointProviderException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * The Microsoft Graph SDK's typed models as the records of {@link SharePointGateway}. The SDK reads the protocol; what
 * MemoryOS accepts from it is decided here: required fields, the length a field may have, and what a page snapshot
 * keeps.
 */
final class GraphModels {
    static final String PAGE_SCHEMA = "memoryos-sharepoint-page-v1";
    static final String DOWNLOAD_URL = "@microsoft.graph.downloadUrl";
    private static final int MAX_FIELD_CHARS = 16_384;
    private static final int MAX_LINK_CHARS = 8_192;
    private static final int MAX_WEB_PARTS = 200;
    private static final int MAX_PART_TEXTS = 200;
    private static final int MAX_PART_CHARS = 200_000;

    private GraphModels() {}

    static SharePointGateway.RootSite rootSite(Site site) {
        var collection = site.getSiteCollection();
        return new SharePointGateway.RootSite(required(site.getId()), required(site.getWebUrl()),
                required(collection == null ? null : collection.getHostname()));
    }

    static SharePointGateway.Site site(Site site) {
        String name = optional(site.getName());
        if (name == null) name = optional(site.getDisplayName());
        return new SharePointGateway.Site(required(site.getId()), required(site.getWebUrl()), name,
                Boolean.TRUE.equals(site.getIsPersonalSite()));
    }

    /** A document library, or null for a drive of another kind. Its path is the library's server-relative one. */
    static SharePointGateway.@Nullable Library library(Drive drive) {
        String type = drive.getDriveType();
        if (!"documentLibrary".equals(type) && !"business".equals(type)) return null;
        String path = URI.create(required(drive.getWebUrl())).getPath();
        if (path == null || path.isBlank()) throw new SharePointProviderException(MALFORMED);
        return new SharePointGateway.Library(required(drive.getId()), required(drive.getName()),
                URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    static SharePointGateway.Folder folder(DriveItem item) {
        if (item.getFolder() == null) throw new SharePointProviderException(SharePointProviderException.Failure.NOT_FOUND);
        return new SharePointGateway.Folder(required(item.getId()), required(item.getName()));
    }

    static SharePointGateway.DriveItem item(DriveItem item, String driveId) {
        var file = item.getFile();
        var hashes = file == null ? null : file.getHashes();
        var parent = item.getParentReference();
        String parentPath = parent == null || parent.getPath() == null ? "" : parent.getPath();
        int root = parentPath.indexOf("root:");
        String parentDrive = parent == null ? null : optional(parent.getDriveId());
        Long size = item.getSize();
        return new SharePointGateway.DriveItem(required(item.getId()), optional(item.getName()), item.getFolder() != null,
                item.getDeleted() != null, size == null ? 0 : size,
                file == null ? null : optional(file.getMimeType()), hashes == null ? null : optional(hashes.getQuickXorHash()),
                optional(item.getETag()), instant(item.getCreatedDateTime()), instant(item.getLastModifiedDateTime()),
                parent == null ? null : optional(parent.getId()),
                root < 0 ? null : URLDecoder.decode(parentPath.substring(root + "root:".length()), StandardCharsets.UTF_8),
                optional(item.getWebUrl()), optional(text(item.getAdditionalData().get(DOWNLOAD_URL))),
                parentDrive == null ? driveId : parentDrive);
    }

    static SharePointGateway.SitePageMetadata pageMetadata(BaseSitePage page) {
        String title = optional(page.getTitle());
        if (title == null) title = optional(page.getName());
        String id = required(page.getId());
        return new SharePointGateway.SitePageMetadata(id, title == null ? id : title, required(page.getWebUrl()),
                optional(page.getETag()), instant(page.getLastModifiedDateTime()));
    }

    /**
     * The page as the reader consumes it: its title and description, the HTML of each text web part, and the
     * searchable text Microsoft prepared for the other web parts. Layout and web part configuration are left out.
     */
    static byte[] snapshot(ObjectMapper mapper, SitePage page, SharePointGateway.SitePageMetadata metadata) {
        var snapshot = mapper.createObjectNode();
        snapshot.put("schema", PAGE_SCHEMA);
        snapshot.put("kind", "SHAREPOINT_PAGE");
        var source = snapshot.putObject("source");
        source.put("id", metadata.pageId());
        source.put("version", metadata.contentVersion());
        var content = snapshot.putObject("content");
        content.put("title", metadata.title());
        content.put("webUrl", metadata.webUrl());
        String description = optional(page.getDescription());
        if (description != null) content.put("description", description);
        var titleArea = page.getTitleArea();
        String above = titleArea == null ? null : titleArea.getTextAboveTitle();
        if (above != null && !above.isBlank()) content.put("textAboveTitle", above);
        var parts = content.putArray("parts");
        var layout = page.getCanvasLayout();
        if (layout != null) {
            for (var section : orEmpty(layout.getHorizontalSections()))
                for (var column : orEmpty(section.getColumns()))
                    for (var webPart : orEmpty(column.getWebparts())) part(parts, webPart);
            if (layout.getVerticalSection() != null)
                for (var webPart : orEmpty(layout.getVerticalSection().getWebparts())) part(parts, webPart);
        }
        return mapper.writeValueAsBytes(snapshot);
    }

    private static void part(ArrayNode parts, WebPart webPart) {
        if (parts.size() >= MAX_WEB_PARTS) throw new SharePointProviderException(LIMIT_EXCEEDED);
        if (webPart instanceof TextWebPart text) {
            String html = text.getInnerHtml();
            if (html == null || html.isBlank()) return;
            var part = parts.addObject();
            part.put("kind", "text");
            part.put("html", clipped(html));
            return;
        }
        if (!(webPart instanceof StandardWebPart standard) || standard.getData() == null) return;
        var data = standard.getData();
        var processed = data.getServerProcessedContent();
        var texts = processed == null ? null : processed.getSearchablePlainTexts();
        String title = data.getTitle();
        if (texts == null && (title == null || title.isBlank())) return;
        var part = parts.addObject();
        part.put("kind", "standard");
        if (title != null && !title.isBlank()) part.put("title", title);
        var values = part.putArray("texts");
        for (var text : orEmpty(texts)) {
            String value = text.getValue();
            if (value == null || value.isBlank()) continue;
            if (values.size() >= MAX_PART_TEXTS) break;
            values.add(clipped(value));
        }
    }

    private static String clipped(String value) {
        return value.length() > MAX_PART_CHARS ? value.substring(0, MAX_PART_CHARS) : value;
    }

    /** A continuation or change link as Graph sent it; null when the answer has none. */
    static @Nullable String link(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > MAX_LINK_CHARS) throw new SharePointProviderException(LIMIT_EXCEEDED);
        return value;
    }

    static <T> List<T> values(@Nullable List<T> value) {
        if (value == null) throw new SharePointProviderException(MALFORMED);
        return value;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> value) {
        return value == null ? List.of() : value;
    }

    private static String required(@Nullable String value) {
        if (value == null || value.isBlank()) throw new SharePointProviderException(MALFORMED);
        if (value.length() > MAX_FIELD_CHARS) throw new SharePointProviderException(LIMIT_EXCEEDED);
        return value;
    }

    private static @Nullable String optional(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > MAX_FIELD_CHARS) throw new SharePointProviderException(LIMIT_EXCEEDED);
        return value;
    }

    /** A value the SDK kept beside the typed fields, as text. */
    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String text ? text : value instanceof UntypedString untyped ? untyped.getValue() : null;
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
