package io.memoryos.chat;

import java.util.UUID;

/** One file of the selection, as asked for. */
public record ChatLibraryArchiveItem(ChatLibraryFile.Source source, UUID id) {}
