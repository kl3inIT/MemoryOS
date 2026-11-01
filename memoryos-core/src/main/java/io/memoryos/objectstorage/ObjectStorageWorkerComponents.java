package io.memoryos.objectstorage;

import org.springframework.context.annotation.ComponentScan;

/**
 * The Worker runs the whole object storage module: stored objects, uploads and their cleanup. The Worker imports this class and scans nothing
 * of the module itself; it is deliberately not a component, so the API, which scans all of {@code io.memoryos},
 * registers these beans only once.
 */
@ComponentScan
public class ObjectStorageWorkerComponents {
}
