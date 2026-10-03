package io.memoryos.chat.image;

import io.memoryos.shared.OutboundHttp;
import io.memoryos.shared.OutboundHttp.Limits;
import io.memoryos.shared.OutboundHttp.ResponseTooLargeException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Trusted administrator-configured image-provider transport; image responses are larger and slower than web calls. */
@Component
public final class ImageHttp {
    private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private final RestClient client = OutboundHttp.builder(new Limits(Duration.ofSeconds(120), 20 * 1024 * 1024)).build();

    public record Response(int status, byte[] bytes) {}
    /** A binary multipart field, such as an input image. */
    public record FilePart(String name, String filename, String contentType, byte[] bytes) {}

    public Response post(URI uri, Map<String, String> headers, String body) throws IOException {
        return execute(client.post().uri(uri).headers(sent -> headers.forEach(sent::set))
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    /** Multipart form for edit endpoints; text fields keep their insertion order and are UTF-8. */
    public Response postMultipart(URI uri, Map<String, String> headers, Map<String, String> fields, List<FilePart> files)
            throws IOException {
        var form = new MultipartBodyBuilder();
        fields.forEach((name, value) -> form.part(name, value, TEXT));
        for (var file : files)
            form.part(file.name(), file.bytes(), MediaType.parseMediaType(file.contentType())).filename(file.filename());
        return execute(client.post().uri(uri).headers(sent -> headers.forEach(sent::set))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form.build()));
    }

    private static Response execute(RestClient.RequestHeadersSpec<?> request) throws IOException {
        try {
            return request.exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                // A provider's error body is neither read nor disclosed.
                if (status < 200 || status >= 300) return new Response(status, new byte[0]);
                return new Response(status, response.getBody().readAllBytes());
            });
        } catch (RestClientException failed) {
            if (failed.getCause() instanceof ResponseTooLargeException) throw new IOException("Image response too large");
            throw failed.getCause() instanceof IOException transport ? transport
                    : new IOException("Image provider request failed");
        }
    }
}
