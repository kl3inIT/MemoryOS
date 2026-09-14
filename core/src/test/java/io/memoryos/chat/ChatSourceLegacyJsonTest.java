package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatSourceLegacyJsonTest {
    @Test
    void readsSourcesSavedBeforePresentationMetadataExisted() throws Exception {
        var saved = """
                [{"citationId":1,"documentId":"4f7f8a4e-8f43-4a8e-9b1f-0d6a1c2b3e4f",
                  "generation":"5a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","title":"HR","startOrdinal":2,"endOrdinal":2,
                  "provenance":[{"ordinal":2,"provenanceJson":"{}"}],"fileId":null,"fileLocation":null,"web":null}]
                """;

        var source = List.of(new ObjectMapper().readValue(saved, ChatSource[].class)).getFirst();

        assertNull(source.mediaType());
        assertEquals(List.of(), source.sourceTypes());
        assertNull(source.providerUrl());
        assertEquals("HR", source.title());
    }
}
