package io.memoryos.chat.image;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** Trusted administrator-configured image-provider transport; image responses are larger and slower than web calls. */
@Component
public final class ImageHttp implements AutoCloseable {
    private static final int LIMIT = 20 * 1024 * 1024;
    private final CloseableHttpClient client = client();

    public record Response(int status, byte[] bytes) {}
    /** A binary multipart field, such as an input image. */
    public record FilePart(String name, String filename, String contentType, byte[] bytes) {}

    private static CloseableHttpClient client() {
        var pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(10).setMaxConnPerRoute(5)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(120)).build());
        return HttpClients.custom().setConnectionManager(pool.build()).disableAutomaticRetries()
                .disableCookieManagement().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(120)).build()).build();
    }

    public Response post(URI uri, Map<String, String> headers, String body) throws IOException {
        var request = new HttpUriRequestBase("POST", uri);
        headers.forEach(request::setHeader);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return execute(request);
    }

    /** Multipart form for edit endpoints; text fields keep their insertion order and are UTF-8. */
    public Response postMultipart(URI uri, Map<String, String> headers, Map<String, String> fields, List<FilePart> files)
            throws IOException {
        var request = new HttpUriRequestBase("POST", uri);
        headers.forEach(request::setHeader);
        var entity = MultipartEntityBuilder.create();
        fields.forEach((name, value) -> entity.addTextBody(name, value, ContentType.TEXT_PLAIN.withCharset(StandardCharsets.UTF_8)));
        for (var file : files) entity.addBinaryBody(file.name(), file.bytes(), ContentType.create(file.contentType()), file.filename());
        request.setEntity(entity.build());
        return execute(request);
    }

    private Response execute(HttpUriRequestBase request) throws IOException {
        return client.execute(request, response -> {
            int status = response.getCode();
            var entity = response.getEntity();
            if (status < 200 || status >= 300) {
                request.cancel(); // Do not drain or disclose provider error bodies.
                return new Response(status, new byte[0]);
            }
            if (entity == null) throw new IOException("Empty image response");
            if (entity.getContentLength() > LIMIT) { request.cancel(); throw new IOException("Image response too large"); }
            byte[] bytes;
            try (var stream = entity.getContent()) {
                bytes = stream.readNBytes(LIMIT + 1);
                if (bytes.length > LIMIT) request.cancel();
            }
            if (bytes.length > LIMIT) { request.cancel(); throw new IOException("Image response too large"); }
            return new Response(status, bytes);
        });
    }
    @jakarta.annotation.PreDestroy
    @Override public void close() throws IOException { client.close(); }
}
