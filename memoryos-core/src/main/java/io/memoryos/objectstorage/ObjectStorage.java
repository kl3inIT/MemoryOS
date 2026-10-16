package io.memoryos.objectstorage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public interface ObjectStorage {
    void write(ObjectKey key, byte[] content, String mediaType);

    /**
     * Writes exactly {@code length} bytes read from {@code content}, which the caller closes, without holding them in
     * memory where the store can stream; the same bounds as {@link #write(ObjectKey, byte[], String)}.
     */
    default void write(ObjectKey key, InputStream content, long length, String mediaType) {
        try {
            byte[] bytes = content.readNBytes((int) Math.min(length, ObjectUploadSpecification.MAX_SIZE_BYTES + 1));
            if (bytes.length != length) throw new IllegalArgumentException("object stream length mismatch");
            write(key, bytes, mediaType);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints);

    ObjectMetadata inspect(ObjectKey key);

    ObjectContent open(ObjectKey key);

    /** Streams bytes {@code first..last} (inclusive); a {@code last} beyond the object end stops at the end. */
    ObjectRangeContent openRange(ObjectKey key, long first, long last);

    void delete(ObjectKey key);
}
