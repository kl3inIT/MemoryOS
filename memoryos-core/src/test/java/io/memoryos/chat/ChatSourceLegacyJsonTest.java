package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.connector.SourceType;
import java.util.List;
import java.util.UUID;
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

        assertNull(source.matchingOrdinal(), "an answer saved before the matching passage was recorded opens as before");
        assertNull(source.mediaType());
        assertEquals(List.of(), source.sourceTypes());
        assertNull(source.providerUrl());
        assertEquals("HR", source.title());
    }

    @Test
    void theMatchingPassageIsKeptAndOnlyADocumentCitationHasOneInsideItsRange() throws Exception {
        var mapper = new ObjectMapper();
        var cited = ChatSource.document(1, UUID.randomUUID(), UUID.randomUUID(), "HR", 2, 5, 4,
                List.of(new ChatSource.Provenance(2, "{}"), new ChatSource.Provenance(4, "{}")));
        assertEquals(4, mapper.readValue(mapper.writeValueAsString(cited), ChatSource.class).matchingOrdinal());
        assertThrows(IllegalArgumentException.class, () -> ChatSource.document(1, UUID.randomUUID(), UUID.randomUUID(),
                "HR", 2, 5, 6, List.of(new ChatSource.Provenance(2, "{}"))));
        assertThrows(IllegalArgumentException.class, () -> new ChatSource(1, null, null, "a.pdf", 0, 0, 0, List.of(),
                UUID.randomUUID(), null, null, null, List.of(), null));
    }

    @Test
    void aProviderLinkIsAnHttpsAddressOfAnyProviderWithoutCredentials() {
        var cited = ChatSource.document(1, UUID.randomUUID(), UUID.randomUUID(), "HR", 0, 0, 0,
                List.of(new ChatSource.Provenance(0, "{}")));
        for (String link : List.of("https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp",
                "https://contoso.sharepoint.com/sites/HR/Shared%20Documents/Leave.docx")) {
            assertEquals(link, cited.described(null, List.of(SourceType.SHAREPOINT), link).providerUrl());
        }
        for (String link : List.of("http://contoso.sharepoint.com/a.docx", "javascript:alert(1)",
                "https://user:secret@contoso.sharepoint.com/a.docx", "https://" + "a".repeat(2048) + ".test/")) {
            assertThrows(IllegalArgumentException.class, () -> cited.described(null, List.of(), link), link);
        }
    }
}
