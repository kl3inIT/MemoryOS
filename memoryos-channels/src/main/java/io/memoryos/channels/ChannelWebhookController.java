package io.memoryos.channels;

import io.memoryos.channel.ChannelSupervisor;
import io.memoryos.channel.ChannelWebhook;
import io.memoryos.shared.OutboundHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The address a platform pushes a bot's messages to (MEM-263), outside {@code /api} and without a session: the bot's
 * webhook checks the platform's secret, and the request is answered once each message it carries is claimed, so a
 * message the platform pushes again is answered once. A request for a bot another process holds is passed to that
 * process, which alone knows the webhook's secret of its opening, and answered as it answered (MEM-283); one for a bot
 * no live process holds is {@code 503}, which the platform retries.
 */
@RestController
final class ChannelWebhookController {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelWebhookController.class);
    /** A platform's message is a few kilobytes; nothing larger is read. */
    static final int MAX_BODY = 64 * 1024;
    /** Marks a request one process passed to another, so it is passed at most once. */
    static final String PASSED = "x-memoryos-passed";
    /** Headers of the connection itself, which the passed request makes its own. */
    private static final Set<String> HOP = Set.of("host", "content-length", "connection", "transfer-encoding",
            "keep-alive", "upgrade", "expect", PASSED);
    /** Within the web proxy's 30 seconds for the route, so the platform hears the holder's answer. */
    private static final OutboundHttp.Limits PASS_ON = new OutboundHttp.Limits(Duration.ofSeconds(25), 4 * 1024);

    private final ChannelSupervisor supervisor;
    private final RestClient peers;

    ChannelWebhookController(ChannelSupervisor supervisor) {
        this.supervisor = supervisor;
        this.peers = OutboundHttp.builder(PASS_ON, (request, response) -> { }).build();
    }

    /** The headers are checked before a byte of the body is read, so a stranger's request costs no read. */
    @PostMapping("/hooks/channels/{connectionId}")
    ResponseEntity<Void> push(@PathVariable UUID connectionId, HttpServletRequest request) throws IOException {
        var headers = headers(request);
        var authorized = supervisor.authorize(connectionId, headers);
        if (authorized == ChannelSupervisor.Delivery.UNKNOWN) return passOn(connectionId, headers, request);
        if (authorized != ChannelSupervisor.Delivery.ACCEPTED) return ResponseEntity.status(status(authorized)).build();
        byte[] body = body(request);
        if (body == null) return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        return ResponseEntity.status(status(supervisor.deliver(connectionId, headers, body))).build();
    }

    /** Passes a request for a bot this process does not hold to the live process that does. */
    private ResponseEntity<Void> passOn(UUID connectionId, Map<String, String> headers, HttpServletRequest request)
            throws IOException {
        var holder = headers.containsKey(PASSED) ? null : supervisor.holderAddress(connectionId).orElse(null);
        if (holder == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        byte[] body = body(request);
        if (body == null) return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        try {
            int status = peers.post().uri("http://" + holder + ChannelWebhook.path(connectionId))
                    .headers(passed -> {
                        headers.forEach((name, value) -> { if (!HOP.contains(name)) passed.set(name, value); });
                        passed.set(PASSED, "1");
                    })
                    .body(body)
                    .exchange((sent, answer) -> answer.getStatusCode().value());
            return ResponseEntity.status(status).build();
        } catch (RestClientException unreachable) {
            LOG.atWarn().addKeyValue("event", "channel.webhook.pass_failed")
                    .addKeyValue("error_type", unreachable.getClass().getName())
                    .log("Channel webhook request could not reach the process holding the bot");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /** The body, or null when it is larger than a platform's message. */
    private static byte @Nullable [] body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY) return null;
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        return body.length > MAX_BODY ? null : body;
    }

    private static HttpStatus status(ChannelSupervisor.Delivery delivery) {
        return switch (delivery) {
            case ACCEPTED -> HttpStatus.OK;
            case REFUSED -> HttpStatus.FORBIDDEN;
            case UNKNOWN -> HttpStatus.NOT_FOUND;
        };
    }

    /** The request's headers by lower-case name, the first value of each. */
    private static Map<String, String> headers(HttpServletRequest request) {
        var headers = new HashMap<String, String>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.putIfAbsent(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }
}
