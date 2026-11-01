package io.memoryos.objectstorage;

import java.io.InputStream;

/** An inclusive byte range of a stored object, with the whole object's size as reported by the provider. */
public interface ObjectRangeContent extends AutoCloseable {
    long first();

    long last();

    long totalBytes();

    InputStream inputStream();

    @Override
    void close();
}
