package io.memoryos.retrieval;

import java.util.List;

/** Adjacent authorized chunks, with the best ranked chunk retained as the selection anchor. */
public record SearchSection(SearchHit anchor, List<SearchHit> chunks) {
    public SearchSection {
        chunks = List.copyOf(chunks);
        if (chunks.isEmpty() || !chunks.contains(anchor)) throw new SearchRequestException();
        int expected = chunks.getFirst().ordinal();
        for (var chunk : chunks) {
            if (!chunk.documentId().equals(anchor.documentId()) || !chunk.generation().equals(anchor.generation())
                    || chunk.ordinal() != expected++) throw new SearchRequestException();
        }
    }

    public int start() { return chunks.getFirst().ordinal(); }
    public int end() { return chunks.getLast().ordinal(); }

    public List<SearchHit> representative() {
        int center = anchor.ordinal() - start();
        int from = Math.clamp(center - 1, 0, Math.max(0, chunks.size() - 3));
        return chunks.subList(from, Math.min(from + 3, chunks.size()));
    }

    public List<SearchPage.Passage> passages() {
        return chunks.stream().map(h -> new SearchPage.Passage(h.ordinal(), h.content(), h.provenanceJson())).toList();
    }
}
