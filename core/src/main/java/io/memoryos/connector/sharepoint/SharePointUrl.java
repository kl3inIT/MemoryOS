package io.memoryos.connector.sharepoint;

import io.memoryos.connector.SharePointException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A SharePoint Online root a Tenant administrator pasted: a site, one of its document libraries, or a
 * folder inside a library. Sharing links are accepted and reduced to the underlying path, as Onyx does.
 * Library and folder segments are kept decoded so matching works on non-English sites, where the library
 * is named "Tài liệu" but its URL segment is still {@code Shared Documents}.
 */
public record SharePointUrl(Kind kind, String host, String sitePath, @Nullable String librarySegment,
                            List<String> folderSegments) {

    public enum Kind { SITE, LIBRARY, FOLDER }

    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(-my)?\\.sharepoint\\.com");
    private static final Pattern SHARE_LINK = Pattern.compile(":[a-z]:");
    private static final List<String> SITE_PREFIXES = List.of("sites", "teams", "personal");
    private static final int MAX_LENGTH = 2048;
    private static final int MAX_SEGMENTS = 64;

    public SharePointUrl {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(sitePath, "sitePath");
        folderSegments = List.copyOf(folderSegments);
    }

    public static SharePointUrl parse(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw SharePointException.invalidRootUrl("Paste a SharePoint site, library or folder address.");
        }
        URI uri;
        try {
            uri = new URI(value.strip());
        } catch (URISyntaxException exception) {
            throw SharePointException.invalidRootUrl("That is not a valid address.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null) {
            throw SharePointException.invalidRootUrl("The address must start with https:// and name a SharePoint host.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!HOST.matcher(host).matches()) {
            throw SharePointException.invalidRootUrl("The address must be on your organization's sharepoint.com host.");
        }
        // The raw path is split first and each segment decoded once, so an encoded "/" stays inside its segment.
        List<String> segments = segments(uri.getRawPath());
        // A sharing link keeps the real path after its ":f:/r" style prefix.
        if (!segments.isEmpty() && SHARE_LINK.matcher(segments.getFirst()).matches()) {
            segments = segments.subList(Math.min(2, segments.size()), segments.size());
        }
        if (segments.size() < 2 || !SITE_PREFIXES.contains(segments.get(0).toLowerCase(Locale.ROOT))) {
            throw SharePointException.invalidRootUrl("The address must contain /sites/, /teams/ or /personal/.");
        }
        String sitePath = "/" + segments.get(0).toLowerCase(Locale.ROOT) + "/" + segments.get(1);
        List<String> rest = segments.subList(2, segments.size());
        if (rest.isEmpty()) return new SharePointUrl(Kind.SITE, host, sitePath, null, List.of());
        // "Forms" holds a library's views, not its content, so a view address means the library itself.
        List<String> folders = new ArrayList<>(rest.subList(1, rest.size()));
        if (!folders.isEmpty() && folders.getFirst().equalsIgnoreCase("Forms")) folders = List.of();
        return folders.isEmpty()
                ? new SharePointUrl(Kind.LIBRARY, host, sitePath, rest.getFirst(), List.of())
                : new SharePointUrl(Kind.FOLDER, host, sitePath, rest.getFirst(), List.copyOf(folders));
    }

    private static List<String> segments(@Nullable String path) {
        if (path == null) return List.of();
        List<String> segments = new ArrayList<>();
        for (String raw : path.split("/")) {
            if (raw.isEmpty()) continue;
            if (segments.size() >= MAX_SEGMENTS) {
                throw SharePointException.invalidRootUrl("That address is nested too deeply.");
            }
            String decoded;
            try {
                // URLDecoder follows form encoding, where "+" means a space; in a path it is a literal plus.
                decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw SharePointException.invalidRootUrl("That address contains an unusable path segment.");
            }
            if (decoded.isBlank() || decoded.contains("\\") || decoded.length() > 255) {
                throw SharePointException.invalidRootUrl("That address contains an unusable path segment.");
            }
            segments.add(decoded);
        }
        return segments;
    }

    /** Whether this root already covers {@code other}, so both must not be selected at once. */
    public boolean covers(SharePointUrl other) {
        if (!host.equals(other.host) || !sitePath.equalsIgnoreCase(other.sitePath)) return false;
        return switch (kind) {
            case SITE -> true;
            case LIBRARY -> sameLibrary(other);
            case FOLDER -> sameLibrary(other) && other.kind == Kind.FOLDER && startsWith(other.folderSegments, folderSegments);
        };
    }

    private boolean sameLibrary(SharePointUrl other) {
        return other.librarySegment != null && other.librarySegment.equalsIgnoreCase(librarySegment);
    }

    private static boolean startsWith(List<String> candidate, List<String> prefix) {
        if (candidate.size() < prefix.size()) return false;
        for (int index = 0; index < prefix.size(); index++) {
            if (!candidate.get(index).equalsIgnoreCase(prefix.get(index))) return false;
        }
        return true;
    }

    /** The personal-site library, which SharePoint calls {@code Documents} rather than {@code Shared Documents}. */
    public boolean personalSite() {
        return sitePath.startsWith("/personal/");
    }

    /** Server-relative path of the selected library or folder, used to match what Graph reports. */
    public String path() {
        StringBuilder path = new StringBuilder(sitePath);
        if (librarySegment != null) path.append('/').append(librarySegment);
        for (String folder : folderSegments) path.append('/').append(folder);
        return path.toString();
    }

    public String canonical() {
        return "https://" + host + path();
    }

    @Override public @NonNull String toString() { return canonical(); }
}
