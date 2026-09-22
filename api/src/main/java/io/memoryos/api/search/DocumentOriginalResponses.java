package io.memoryos.api.search;

import io.memoryos.retrieval.DocumentOriginalService.ByteRange;
import io.memoryos.retrieval.DocumentOriginalService.Original;
import io.memoryos.retrieval.DocumentOriginalService.RangeNotSatisfiableException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Original bytes are served as a non-executable, uncached attachment, like owner-private Chat files.
 * A single {@code Range} lets pdf.js read only the parts of a large PDF it renders; every range is a new
 * authorized read.
 */
public final class DocumentOriginalResponses {
    private static final Pattern SINGLE_RANGE = Pattern.compile("bytes=(\\d{1,18})-(\\d{0,18})", Pattern.CASE_INSENSITIVE);

    private DocumentOriginalResponses() {}

    public static void write(@Nullable String rangeHeader, HttpServletResponse response,
            Function<@Nullable ByteRange, Original> open) throws IOException {
        Original opened;
        try {
            opened = open.apply(parseRange(rangeHeader));
        } catch (RangeNotSatisfiableException unsatisfiable) {
            commonHeaders(response);
            response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + unsatisfiable.sizeBytes());
            return;
        }
        try (var original = opened) {
            commonHeaders(response);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(original.reference().filename(), StandardCharsets.UTF_8).build().toString());
            // Stream through the proxy instead of spooling a whole original to its temporary files.
            response.setHeader("X-Accel-Buffering", "no");
            long size = original.reference().metadata().sizeBytes();
            var range = original.range();
            if (range == null) {
                response.setContentLengthLong(size);
            } else {
                response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + range.first() + "-" + range.last() + "/" + size);
                response.setContentLengthLong(range.length());
            }
            original.inputStream().transferTo(response.getOutputStream());
        }
    }

    /**
     * Accepts one {@code bytes=first-} or {@code bytes=first-last} range. Suffix, multiple and malformed ranges are
     * ignored and the whole original is served, which RFC 9110 permits; pdf.js sends only the accepted form.
     */
    static @Nullable ByteRange parseRange(@Nullable String header) {
        if (header == null) return null;
        var matcher = SINGLE_RANGE.matcher(header.strip());
        if (!matcher.matches()) return null;
        long first = Long.parseLong(matcher.group(1));
        long last = matcher.group(2).isEmpty() ? Long.MAX_VALUE : Long.parseLong(matcher.group(2));
        return last < first ? null : new ByteRange(first, last);
    }

    private static void commonHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }
}
