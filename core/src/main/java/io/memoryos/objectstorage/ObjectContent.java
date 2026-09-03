package io.memoryos.objectstorage;

import java.io.InputStream;

public interface ObjectContent extends AutoCloseable {
    ObjectMetadata metadata();

    InputStream inputStream();

    @Override
    void close();
}
