package io.memoryos.api.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The same-origin guard for browser-session mutations: a non-simple request header the browser client
 * sends on every unsafe API request, which cross-site forms cannot forge.
 */
public final class BrowserMutation {

    public static final String HEADER = "X-MemoryOS-CSRF";
    public static final String VALUE = "1";
    public static final String DESCRIPTION =
            "Same-origin non-simple request guard for browser-session mutations.";

    private BrowserMutation() {
    }

    static boolean isPresent(String headerValue) {
        return VALUE.equals(headerValue);
    }

    static void require(String headerValue) {
        if (!isPresent(headerValue)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "same-origin browser request required");
        }
    }
}
