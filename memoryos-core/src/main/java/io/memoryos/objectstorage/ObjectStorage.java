package io.memoryos.objectstorage;

import io.memoryos.shared.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public interface ObjectStorage {
    void write(ObjectKey key, byte[] content, String mediaType);

    /**
     * Writes the bytes read from {@code content}, which the caller closes, without holding them in memory where the
     * store can stream. The caller declares their size and SHA-256 up front, as every stored object carries its
     * checksum, and the store refuses a body that does not match; the same bounds as
     * {@link #write(ObjectKey, byte[], String)}.
     */
    default void writeStream(ObjectKey key, InputStream content, ObjectMetadata declared) {
        try {
            byte[] bytes = content.readNBytes((int) Math.min(declared.sizeBytes(), ObjectUploadSpecification.MAX_SIZE_BYTES + 1));
            if (bytes.length != declared.sizeBytes() || !Sha256.hex(bytes).equals(declared.checksum().value()))
                throw new IllegalArgumentException("object stream does not match its declared metadata");
            write(key, bytes, declared.mediaType());
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
