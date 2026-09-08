package io.memoryos.retrieval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.retrieval.embedding.ValidatedEmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

class ValidatedEmbeddingServiceTest {
    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final ValidatedEmbeddingService service = new ValidatedEmbeddingService(model, "text-embedding-3-large", 3, 32, 2);

    private EmbeddingResponse response(String name, Embedding... values) {
        return new EmbeddingResponse(List.of(values), new EmbeddingResponseMetadata(name, new EmptyUsage()));
    }

    @Test
    void mapsUnorderedProviderResultsBackToTheCorrectInputAndCopiesVectors() {
        float[] first = {1, 0, 0};
        when(model.call(any())).thenReturn(response("text-embedding-3-large",
                new Embedding(new float[]{0, 1, 0}, 1), new Embedding(first, 0)));
        var values = service.batch(List.of("quy định nghỉ phép", "quy trình thanh toán"));
        assertArrayEquals(first, values.getFirst());
        assertArrayEquals(new float[]{0, 1, 0}, values.get(1));
        first[0] = 99;
        assertEquals(1, values.getFirst()[0]);
    }

    @Test
    void rejectsWrongModelCountDimensionDuplicateIndexAndInvalidNumbers() {
        var malformed = List.of(
                response("other-model", new Embedding(new float[]{1, 0, 0}, 0)),
                response("text-embedding-3-large"),
                response("text-embedding-3-large", new Embedding(new float[]{1, 0}, 0)),
                response("text-embedding-3-large", new Embedding(new float[]{Float.NaN, 0, 0}, 0)),
                response("text-embedding-3-large", new Embedding(new float[]{0, 0, 0}, 0)),
                response("text-embedding-3-large", new Embedding(new float[]{1, 0, 0}, 2)));
        for (var value : malformed) {
            when(model.call(any())).thenReturn(value);
            assertThrows(SearchUnavailableException.class, () -> service.query("test"));
        }
        when(model.call(any())).thenReturn(response("text-embedding-3-large",
                new Embedding(new float[]{1, 0, 0}, 0), new Embedding(new float[]{0, 1, 0}, 0)));
        assertThrows(SearchUnavailableException.class, () -> service.batch(List.of("one", "two")));
    }

    @Test
    void providerErrorsNeverExposeRequestPayloadOrCredentials() {
        when(model.call(any())).thenThrow(new IllegalStateException("Authorization: secret; private document"));
        var error = assertThrows(SearchUnavailableException.class, () -> service.query("private query"));
        assertNull(error.getCause());
        assertFalse(error.toString().contains("secret"));
        assertFalse(error.toString().contains("private"));
    }
}
