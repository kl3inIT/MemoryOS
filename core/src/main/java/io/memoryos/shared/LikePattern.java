package io.memoryos.shared;

/**
 * A person's search text inside a SQL {@code LIKE} or {@code ILIKE} pattern. {@code %} and {@code _} are wildcards
 * there, so a search for "100%" would find everything; they are escaped with a backslash, which is PostgreSQL's
 * default {@code ESCAPE} character and the one every query here names.
 */
public final class LikePattern {
    private LikePattern() {}

    /** The text with the pattern's wildcards and its escape character taken literally. */
    public static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** A pattern matching any value that contains the text. */
    public static String containing(String text) {
        return "%" + escape(text) + "%";
    }
}
