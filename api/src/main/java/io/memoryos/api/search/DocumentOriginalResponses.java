package io.memoryos.api.search;

import io.memoryos.objectstorage.StoredObjectReference;
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
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Original bytes of any media type, served uncached under the object's declared type so the viewer can
 * render the file as it looks rather than its extraction. Nothing is ever sniffed, and anything that could
 * run script in this origin is sent as an attachment instead of shown. A single {@code Range} lets pdf.js
 * read only the parts of a large PDF it renders; every range is a new authorized read.
 */
public final class DocumentOriginalResponses {
    /** Types a browser would run as script in this origin if it displayed them. */
    private static final java.util.Set<String> SCRIPTABLE =
            java.util.Set.of("text/html", "image/svg+xml", "application/xhtml+xml");

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
        try (var pdf = opened) {
            commonHeaders(response);
            contentHeaders(response, pdf.reference());
            // Stream through the proxy instead of spooling a whole original to its temporary files.
            response.setHeader("X-Accel-Buffering", "no");
            long size = pdf.reference().metadata().sizeBytes();
            var range = pdf.range();
            if (range == null) {
                response.setContentLengthLong(size);
            } else {
                response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + range.first() + "-" + range.last() + "/" + size);
                response.setContentLengthLong(range.length());
            }
            pdf.inputStream().transferTo(response.getOutputStream());
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

    /**
     * The object's declared type, so the browser and pdf.js can render the original rather than guess at
     * bytes. Types that could run script in this origin are still sent, but as an attachment: the viewer
     * never asks to display one, and this is the server-side guarantee that it cannot be displayed. A type
     * the object never usefully declared falls back to bytes, which nothing will try to render.
     */
    private static void contentHeaders(HttpServletResponse response, StoredObjectReference reference) {
        MediaType declared;
        try {
            declared = MediaType.parseMediaType(reference.metadata().mediaType());
        } catch (InvalidMediaTypeException unusable) {
            declared = MediaType.APPLICATION_OCTET_STREAM;
        }
        boolean shown = !SCRIPTABLE.contains(declared.getType() + "/" + declared.getSubtype())
                && !MediaType.APPLICATION_OCTET_STREAM.equalsTypeAndSubtype(declared);
        var disposition = shown ? ContentDisposition.inline() : ContentDisposition.attachment();
        response.setContentType(declared.toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                disposition.filename(reference.filename(), StandardCharsets.UTF_8).build().toString());
    }

    private static void commonHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
    }
}
