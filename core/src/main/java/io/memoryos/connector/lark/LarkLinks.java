package io.memoryos.connector.lark;

import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.SourceException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The addresses Lark gives its objects, read without a call to Lark. */
final class LarkLinks {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,128}");
    private static final Pattern SPACE_ID = Pattern.compile("[0-9]{6,32}");
    private static final String FOLDER_PATH = "/drive/folder/";

    private LarkLinks() {
    }

    /** A Wiki space ID, as Lark's own listing returns it. */
    static String spaceId(String value) {
        String trimmed = value == null ? "" : value.strip();
        if (!SPACE_ID.matcher(trimmed).matches())
            throw SourceException.invalidRootLink("Choose a Wiki space from the list.");
        return trimmed;
    }

    /**
     * The token of a Drive folder, from its link ({@code https://<tenant>.larksuite.com/drive/folder/<token>}) or
     * from the token itself. A link on another host than the credential's region is refused.
     */
    static String folderToken(String value, LarkGateway.Region region) {
        String trimmed = value == null ? "" : value.strip();
        if (TOKEN.matcher(trimmed).matches()) return trimmed;
        try {
            var uri = new URI(trimmed);
            String host = uri.getHost();
            String path = uri.getPath();
            if ("https".equalsIgnoreCase(uri.getScheme()) && host != null && uri.getRawUserInfo() == null
                    && onRegion(host, region) && path != null && path.startsWith(FOLDER_PATH)) {
                String token = path.substring(FOLDER_PATH.length()).replaceAll("/+$", "");
                if (TOKEN.matcher(token).matches()) return token;
            }
        } catch (URISyntaxException malformed) {
            // Refused below with the same explanation.
        }
        throw SourceException.invalidRootLink("Paste the link of a Lark Drive folder, as its address bar shows it.");
    }

    /** Where a reader opens an object, when Lark returned an address on a Lark host; null otherwise. */
    static @Nullable String documentUrl(@Nullable String sourceUrl) {
        if (sourceUrl == null || sourceUrl.length() > 2048) return null;
        try {
            var uri = new URI(sourceUrl);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null && host != null
                    && (onRegion(host, LarkGateway.Region.LARK) || onRegion(host, LarkGateway.Region.FEISHU))
                    ? sourceUrl : null;
        } catch (URISyntaxException malformed) {
            return null;
        }
    }

    private static boolean onRegion(String host, LarkGateway.Region region) {
        String lower = host.toLowerCase(Locale.ROOT);
        return switch (region) {
            case LARK -> lower.equals("larksuite.com") || lower.endsWith(".larksuite.com");
            case FEISHU -> lower.equals("feishu.cn") || lower.endsWith(".feishu.cn");
        };
    }
}
