package io.memoryos.chat;

import java.util.ArrayList;
import java.util.List;

/** A bounded per-turn collector; terminal outcomes seal it against late provider callbacks. */
public final class ChatArtifacts {
    private final List<ChatArtifact> items = new ArrayList<>();
    private boolean sealed;

    public synchronized boolean add(ChatArtifact artifact) {
        if (sealed || items.size() >= 3) return false;
        items.add(artifact);
        return true;
    }

    public synchronized List<ChatArtifact> seal() {
        sealed = true;
        return List.copyOf(items);
    }
}
