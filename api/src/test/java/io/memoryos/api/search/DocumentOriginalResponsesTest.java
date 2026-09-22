package io.memoryos.api.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.retrieval.DocumentOriginalService.ByteRange;
import io.memoryos.retrieval.DocumentOriginalService.Original;
import io.memoryos.retrieval.DocumentOriginalService.RangeNotSatisfiableException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class DocumentOriginalResponsesTest {
    private static final byte[] PDF = "%PDF-1.7 ranged".getBytes(StandardCharsets.US_ASCII);
    private static final StoredObjectReference REFERENCE = new StoredObjectReference(
            new StoredObjectId(UUID.nameUUIDFromBytes("pdf".getBytes(StandardCharsets.US_ASCII))),
            new ObjectKey("raw/tenant/pdf"), "Sổ tay.pdf",
            new ObjectMetadata(PDF.length, "application/pdf", new ContentSha256("a".repeat(64))));

    @Test
    void acceptsOnlyOneOrderedByteRange() {
        assertEquals(new ByteRange(0, 1_048_575), DocumentOriginalResponses.parseRange("bytes=0-1048575"));
        assertEquals(new ByteRange(7, Long.MAX_VALUE), DocumentOriginalResponses.parseRange("Bytes=7-"));
        assertNull(DocumentOriginalResponses.parseRange(null));
        assertNull(DocumentOriginalResponses.parseRange("bytes=-500"));
        assertNull(DocumentOriginalResponses.parseRange("bytes=0-1,4-5"));
        assertNull(DocumentOriginalResponses.parseRange("bytes=9-3"));
        assertNull(DocumentOriginalResponses.parseRange("items=0-1"));
        assertNull(DocumentOriginalResponses.parseRange("bytes=99999999999999999999-"));
    }

    @Test
    void wholeOriginalAdvertisesRangesAndStreamsWithoutProxyBuffering() throws Exception {
        var response = new MockHttpServletResponse();
        var requested = new AtomicReference<ByteRange>(new ByteRange(1, 1));
        var closed = new AtomicBoolean();
        DocumentOriginalResponses.write(null, response, range -> {
            requested.set(range);
            return new Original(REFERENCE, null, new ByteArrayInputStream(PDF), () -> closed.set(true));
        });

        assertNull(requested.get());
        assertEquals(200, response.getStatus());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertEquals(PDF.length, response.getContentLengthLong());
        assertNull(response.getHeader("Content-Range"));
        assertEquals("application/octet-stream", response.getContentType());
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertArrayEquals(PDF, response.getContentAsByteArray());
        assertTrue(closed.get());
    }

    @Test
    void servedRangeIsPartialContentWithItsOwnLength() throws Exception {
        var response = new MockHttpServletResponse();
        var requested = new AtomicReference<ByteRange>();
        var closed = new AtomicBoolean();
        DocumentOriginalResponses.write("bytes=5-", response, range -> {
            requested.set(range);
            var served = new ByteRange(5, PDF.length - 1);
            return new Original(REFERENCE, served,
                    new ByteArrayInputStream(Arrays.copyOfRange(PDF, 5, PDF.length)), () -> closed.set(true));
        });

        assertEquals(new ByteRange(5, Long.MAX_VALUE), requested.get());
        assertEquals(206, response.getStatus());
        assertEquals("bytes 5-" + (PDF.length - 1) + "/" + PDF.length, response.getHeader("Content-Range"));
        assertEquals(PDF.length - 5, response.getContentLengthLong());
        assertEquals("bytes", response.getHeader("Accept-Ranges"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertArrayEquals(Arrays.copyOfRange(PDF, 5, PDF.length), response.getContentAsByteArray());
        assertTrue(closed.get());
    }

    @Test
    void rangeBeyondTheOriginalIsNotSatisfiableWithoutBody() throws Exception {
        var response = new MockHttpServletResponse();
        DocumentOriginalResponses.write("bytes=" + PDF.length + "-", response, range -> {
            throw new RangeNotSatisfiableException(PDF.length);
        });

        assertEquals(416, response.getStatus());
        assertEquals("bytes */" + PDF.length, response.getHeader("Content-Range"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(response.getHeader("Content-Disposition"));
        assertEquals(0, response.getContentAsByteArray().length);
    }
}
