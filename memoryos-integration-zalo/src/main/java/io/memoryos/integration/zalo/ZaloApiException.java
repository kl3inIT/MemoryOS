package io.memoryos.integration.zalo;

/**
 * A Zalo call that failed, by its code alone: Zalo's {@code error_code}, else the HTTP status, else {@link #NO_ANSWER}.
 * It never carries Zalo's text, the request's address (which holds the token) or a cause that would.
 */
final class ZaloApiException extends RuntimeException {
    static final int NO_ANSWER = -1;

    private final int code;

    ZaloApiException(int code) {
        super("Zalo call failed: " + code, null, false, false);
        this.code = code;
    }

    int code() {
        return code;
    }

    /** The token is wrong or was reset. */
    boolean rejected() {
        return code == 401 || code == 403;
    }

    /** The bot's plan has no messages left this month. */
    boolean quota() {
        return code == 429;
    }

    /** A poll that waited its whole time and got nothing. */
    boolean idle() {
        return code == 408;
    }

    /** The day's webhook checks are spent. */
    boolean checksSpent() {
        return code == 426;
    }
}
