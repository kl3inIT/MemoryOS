package io.memoryos.chat.web;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Separate credential-free public reader and trusted administrator-configured provider transport. */
@Component
public final class WebHttp implements AutoCloseable {
    private final CloseableHttpClient providers = client(false);
    private final CloseableHttpClient pages = client(true);
    public record Response(int status, String contentType, byte[] bytes, @Nullable String location) {}

    private static CloseableHttpClient client(boolean publicOnly) {
        var pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(20).setMaxConnPerRoute(5)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                        .setSocketTimeout(Timeout.ofSeconds(15)).build());
        if (publicOnly) pool.setDnsResolver(new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                var addresses = InetAddress.getAllByName(host);
                for (var address : addresses) if (!publicAddress(address)) throw new UnknownHostException("Non-public Web destination");
                return addresses; // These exact validated addresses are used for the socket; no second DNS lookup.
            }
            @Override public String resolveCanonicalHostname(String host) { return host; }
        });
        return HttpClients.custom().setConnectionManager(pool.build()).disableAutomaticRetries()
                .disableCookieManagement().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(15)).build()).build();
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int a = b[0] & 255;
        if (b.length == 16) return (a & 0xe0) == 0x20 // Public global unicast only.
                && !(a == 0x20 && b[1] == 1 && b[2] == 0 && b[3] == 0) // Teredo.
                && !(a == 0x20 && b[1] == 2); // 6to4 may embed a private IPv4 address.
        int second = b[1] & 255;
        return a != 0 && a < 224 && !(a == 100 && second >= 64 && second <= 127)
                && !(a == 198 && (second == 18 || second == 19));
    }

    public static URI pageUri(String url) {
        try {
            var uri = URI.create(url);
            if (url.length() > 2048 || uri.getHost() == null || uri.getRawUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException();
            // HttpClient can bypass DNS for literal IPs. Validate them here too.
            String host = uri.getHost().replace("[", "").replace("]", "");
            if ((host.contains(":") || host.matches("[0-9.]+")) && !publicAddress(InetAddress.getByName(host)))
                throw new IllegalArgumentException();
            return uri;
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid public Web URL"); }
    }

    public Response provider(String method, URI uri, Map<String, String> headers, @Nullable String body) throws IOException {
        return request(providers, method, uri, headers, body, 2 * 1024 * 1024);
    }
    public Response page(String url, Runnable checkActive) throws IOException {
        URI uri = pageUri(url);
        for (int redirects = 0; redirects <= 10; redirects++) {
            checkActive.run();
            var response = request(pages, "GET", uri, Map.of("User-Agent", "MemoryOSWebReader/1.0",
                    "Accept", "text/html,text/plain,application/xhtml+xml"), null, 20 * 1024 * 1024);
            checkActive.run();
            if (response.status() < 300 || response.status() >= 400) return response;
            if (response.location() == null) throw new IOException("Invalid Web redirect");
            uri = pageUri(uri.resolve(response.location()).toString());
        }
        throw new IOException("Too many Web redirects");
    }

    private static Response request(CloseableHttpClient client, String method, URI uri, Map<String, String> headers,
                                    @Nullable String body, int limit) throws IOException {
        var request = new HttpUriRequestBase(method, uri);
        headers.forEach(request::setHeader);
        if (body != null) request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        return client.execute(request, response -> {
            int status = response.getCode();
            var location = response.getFirstHeader("Location");
            if (status < 200 || status >= 300) {
                request.cancel(); // Do not drain an unbounded error/redirect body or disclose provider error text.
                return new Response(status, "", new byte[0], location == null ? null : location.getValue());
            }
            var entity = response.getEntity();
            if (entity == null) throw new IOException("Empty Web response");
            if (entity.getContentLength() > limit) { request.cancel(); throw new IOException("Web response too large"); }
            byte[] bytes;
            try (var stream = entity.getContent()) {
                bytes = stream.readNBytes(limit + 1);
                if (bytes.length > limit) request.cancel(); // Abort before stream.close could drain more bytes.
            }
            if (bytes.length > limit) { request.cancel(); throw new IOException("Web response too large"); }
            return new Response(status, entity.getContentType() == null ? "" : entity.getContentType(), bytes, null);
        });
    }
    @PreDestroy
    @Override public void close() throws IOException {
        try { providers.close(); } finally { pages.close(); }
    }
}
