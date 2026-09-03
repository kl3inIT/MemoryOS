package io.memoryos.objectstorage;

public interface ObjectStorage {
    UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints);

    ObjectMetadata inspect(ObjectKey key);

    ObjectContent open(ObjectKey key);

    void delete(ObjectKey key);
}
