package io.memoryos.objectstorage;

public interface ObjectStorage {
    void write(ObjectKey key, byte[] content, String mediaType);

    UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints);

    ObjectMetadata inspect(ObjectKey key);

    ObjectContent open(ObjectKey key);

    /** Streams bytes {@code first..last} (inclusive); a {@code last} beyond the object end stops at the end. */
    ObjectRangeContent openRange(ObjectKey key, long first, long last);

    void delete(ObjectKey key);
}
