package io.memoryos.ingestion.extraction;

import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrProvider;
import io.memoryos.ai.ocr.OcrServiceUsage;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI Hay's OCR ({@code POST /v1/ocr}): one JPEG or PNG as base64 in, the picture's text as Markdown out. The kind of a
 * failure is read from the status, never from what the service wrote.
 */
final class AiHayOcrAdapter implements OcrAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(AiHayOcrAdapter.class);
    /** AI Hay refuses a body over 2 MB, counted in its JSON characters, and base64 adds a third. */
    static final int MAX_PICTURE_BYTES = 1_400_000;
    /** A page of text is a few kilobytes; the cap bounds a hostile endpoint. */
    private static final int MAX_ANSWER_BYTES = 4 * 1_048_576;
    private final OcrServiceUsage usage;
    private final Duration timeout;
    private final ObjectMapper mapper;

    AiHayOcrAdapter(OcrServiceUsage usage, Duration timeout, ObjectMapper mapper) {
        this.usage = usage;
        this.timeout = timeout;
        this.mapper = mapper;
    }

    @Override public OcrProvider provider() { return OcrProvider.AIHAY; }

    @Override public OcrReader reader(OcrConnectionService.Connection connection, String key) {
        // The configured endpoint must not redirect this key elsewhere, and its answer is bounded.
        RestClient client = OutboundHttp.builder(new Limits(timeout, MAX_ANSWER_BYTES)).build();
        URI address = URI.create(connection.endpoint().replaceAll("/+$", "").replaceAll("/v1$", "") + "/v1/ocr");
        return new PagePictureOcrReader((picture, mediaType) -> {
            String text = read(client, address, key, picture);
            usage.page(connection);
            return text;
        }, new PagePictureOcrReader.Settings("aihay-ocr", "v1", Set.of("image/png", "image/jpeg"), MAX_PICTURE_BYTES),
                mapper);
    }

    private String read(RestClient client, URI address, String key, byte[] picture) throws ExtractionException {
        // Base64 holds no character JSON would escape.
        byte[] body = ("{\"image\":\"" + Base64.getEncoder().encodeToString(picture) + "\"}").getBytes(StandardCharsets.US_ASCII);
        Answer answer;
        try {
            answer = client.post().uri(address).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + key).body(body)
                    .exchange((request, response) -> new Answer(response.getStatusCode().value(),
                            response.getBody().readAllBytes()));
        } catch (RestClientException unreachable) {
            throw failed(ExtractionFailure.CONNECTION_FAILED, -1);
        }
        int status = answer.status();
        // A picture the service will not read is a page without text, so the next pages are still read.
        if (status == 400 || status == 413) return "";
        if (status == 429 || status >= 500) throw failed(ExtractionFailure.CONNECTION_FAILED, status);
        // A refused key, a redirect or any other answer is the connection's configuration, which a retry does not mend.
        if (status != 200) throw failed(ExtractionFailure.INTERNAL, status);
        try {
            JsonNode text = mapper.readTree(answer.body()).path("result").path("text");
            if (!text.isString()) throw failed(ExtractionFailure.INTERNAL, status);
            return text.asString().strip();
        } catch (JacksonException notJson) {
            throw failed(ExtractionFailure.INTERNAL, status);
        }
    }

    private static ExtractionException failed(ExtractionFailure failure, int status) {
        LOG.atWarn().addKeyValue("event", "aihay_ocr.page.failed").addKeyValue("error_code", failure.name())
                .addKeyValue("http_status", status).log("AI Hay did not read a page");
        return DocumentAssembly.failure(failure);
    }

    private record Answer(int status, byte[] body) {}
}
