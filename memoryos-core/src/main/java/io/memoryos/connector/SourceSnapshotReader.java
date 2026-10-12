package io.memoryos.connector;

import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import java.io.InputStream;

/**
 * Reads the snapshot a provider's synchronization stores in one of its own input formats into the document model.
 * Each provider integration contributes the readers of the formats it writes, and extraction chooses one by the
 * format of the input, so it names no provider.
 */
public interface SourceSnapshotReader {
    /** The input format this reader reads; exactly one reader exists for a format. */
    SourceInputFormat format();

    DocumentContent extract(InputStream content, long size, String filename, SourceInputDescriptor input)
            throws ExtractionException;
}
