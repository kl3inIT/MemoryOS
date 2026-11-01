package io.memoryos.library;

import java.util.UUID;

/** One file of the selection, as asked for. */
public record LibraryArchiveItem(LibraryFile.Source source, UUID id) {}
