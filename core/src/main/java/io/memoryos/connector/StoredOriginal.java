package io.memoryos.connector;

import io.memoryos.objectstorage.StoredObjectReference;

/**
 * The stored source object a Document was extracted from, with the Document's own media type. A reader picks its
 * viewer from that media type, so the byte check and the viewer agree on one value rather than on the object's
 * declared type, which an upload or a provider may report differently.
 */
public record StoredOriginal(StoredObjectReference reference, String mediaType) {}
