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
        var values = service.documents(List.of("quy định nghỉ phép", "quy trình thanh toán"), null);
        assertArrayEquals(first, values.getFirst());
        assertArrayEquals(new float[]{0, 1, 0}, values.get(1));
        first[0] = 99;
        assertEquals(1, values.getFirst()[0]);
    }

    @Test
    void recordsReportedTokensForAKnownCallerOnlyAndPricesThemWhenConfigured() {
        var recorder = mock(io.memoryos.usage.AiUsageRecorder.class);
        var priced = new ValidatedEmbeddingService(model, "text-embedding-3-large", 3, 32, 2, recorder, "api.openai.com", 0.13, "", "");
        var tenant = java.util.UUID.randomUUID();
        when(model.call(any())).thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1, 0, 0}, 0)),
                new EmbeddingResponseMetadata("text-embedding-3-large", new org.springframework.ai.chat.metadata.DefaultUsage(1000, 0))));
        priced.query("quy định", new ValidatedEmbeddingService.Caller(tenant, null, io.memoryos.usage.AiUsageFlow.EMBEDDING_INDEXING));
        priced.query("không ghi");
        var captured = org.mockito.ArgumentCaptor.forClass(io.memoryos.usage.AiUsage.class);
        org.mockito.Mockito.verify(recorder).record(captured.capture());
        assertEquals(1000, captured.getValue().inputTokens());
        assertEquals(0.00013, captured.getValue().cost(), 1e-12);
        assertEquals(io.memoryos.usage.AiUsageFlow.EMBEDDING_INDEXING, captured.getValue().flow());
        assertNull(captured.getValue().actor());
        when(model.call(any())).thenReturn(response("text-embedding-3-large", new Embedding(new float[]{1, 0, 0}, 0)));
        priced.query("không có usage", new ValidatedEmbeddingService.Caller(tenant, tenant, io.memoryos.usage.AiUsageFlow.EMBEDDING_QUERY));
        org.mockito.Mockito.verify(recorder, org.mockito.Mockito.times(2)).record(captured.capture());
        assertNull(captured.getValue().cost());
        assertEquals(0, captured.getValue().inputTokens());
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
        assertThrows(SearchUnavailableException.class, () -> service.documents(List.of("one", "two"), null));
    }

    @Test
    void questionsAndPassagesAreEmbeddedWithTheirOwnPrefixes() {
        String instruction = "Instruct: Given a question, retrieve passages that answer it\nQuery: ";
        var qwen = new ValidatedEmbeddingService(model, "Qwen/Qwen3-Embedding-0.6B", 3, 32, 2, null, "serving", null,
                instruction, "");
        var sent = new java.util.ArrayList<List<String>>();
        when(model.call(any())).thenAnswer(call -> {
            org.springframework.ai.embedding.EmbeddingRequest request = call.getArgument(0);
            sent.add(List.copyOf(request.getInstructions()));
            var values = new java.util.ArrayList<Embedding>();
            for (int i = 0; i < request.getInstructions().size(); i++) values.add(new Embedding(new float[]{1, 0, 0}, i));
            return new EmbeddingResponse(values, new EmbeddingResponseMetadata("Qwen/Qwen3-Embedding-0.6B", new EmptyUsage()));
        });
        qwen.query("Chính sách nghỉ phép năm 2026?");
        qwen.queries(List.of("Ai duyệt nghỉ phép?"), null);
        qwen.documents(List.of("Nhân viên có 12 ngày nghỉ phép."), null);
        assertEquals(List.of(
                List.of(instruction + "Chính sách nghỉ phép năm 2026?"),
                List.of(instruction + "Ai duyệt nghỉ phép?"),
                List.of("Nhân viên có 12 ngày nghỉ phép.")), sent);
        var passagePrefixed = new ValidatedEmbeddingService(model, "Qwen/Qwen3-Embedding-0.6B", 3, 32, 2, null, "serving", null,
                "query: ", "passage: ");
        sent.clear();
        passagePrefixed.documents(List.of("đoạn văn"), null);
        passagePrefixed.query("câu hỏi");
        assertEquals(List.of(List.of("passage: đoạn văn"), List.of("query: câu hỏi")), sent);
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
