package io.memoryos.document;

/**
 * The extracted artifact cannot be indexed because of its content, not because of a transient
 * failure. The code is a stable uppercase token stored on the document and shown to the user;
 * retrying the same artifact always fails the same way.
 */
public final class DocumentContentException extends IllegalArgumentException {
    private final String code;

    public DocumentContentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
