package io.memoryos.channels;

import io.memoryos.channel.ChannelSupervisor;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The address a platform pushes a bot's messages to (MEM-263), outside {@code /api} and without a session: the bot's
 * webhook checks the platform's secret, and the request is answered once each message it carries is claimed, so a
 * message the platform pushes again is answered once.
 */
@RestController
final class ChannelWebhookController {
    /** A platform's message is a few kilobytes; nothing larger is read. */
    static final int MAX_BODY = 64 * 1024;

    private final ChannelSupervisor supervisor;

    ChannelWebhookController(ChannelSupervisor supervisor) {
        this.supervisor = supervisor;
    }

    /** The headers are checked before a byte of the body is read, so a stranger's request costs no read. */
    @PostMapping("/hooks/channels/{connectionId}")
    ResponseEntity<Void> push(@PathVariable UUID connectionId, HttpServletRequest request) throws IOException {
        var headers = headers(request);
        var authorized = supervisor.authorize(connectionId, headers);
        if (authorized != ChannelSupervisor.Delivery.ACCEPTED) return ResponseEntity.status(status(authorized)).build();
        if (request.getContentLengthLong() > MAX_BODY) return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        return ResponseEntity.status(status(supervisor.deliver(connectionId, headers, body))).build();
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
