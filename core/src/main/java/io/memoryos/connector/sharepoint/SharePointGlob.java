package io.memoryos.connector.sharepoint;

import io.memoryos.connector.SharePointException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Exclusion pattern for a site address or an item path. The wildcards follow Onyx, which uses shell-style
 * matching: {@code *} spans path separators and {@code ?} matches one character. Matching ignores case
 * because SharePoint paths do.
 */
public record SharePointGlob(String pattern, Pattern compiled) {
    public static final int MAX_PATTERN_CHARS = 512;
    public static final int MAX_PATTERNS = 100;

    public SharePointGlob {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(compiled, "compiled");
    }

    public static SharePointGlob of(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.strip().length() > MAX_PATTERN_CHARS) {
            throw SharePointException.invalidExclusion();
        }
        String normalized = pattern.strip();
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            switch (character) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(character)));
            }
        }
        // UNICODE_CASE matters: site and file names are often Vietnamese.
        return new SharePointGlob(normalized,
                Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    public static List<SharePointGlob> all(List<String> patterns) {
        if (patterns.size() > MAX_PATTERNS) throw SharePointException.invalidExclusion();
        return patterns.stream().map(SharePointGlob::of).toList();
    }

    public boolean matches(String value) {
        return value != null && compiled.matcher(value).matches();
    }

    /** True when any pattern matches; an empty list excludes nothing. */
    public static boolean excluded(List<SharePointGlob> patterns, String value) {
        return patterns.stream().anyMatch(glob -> glob.matches(value));
    }

    @Override public String toString() { return pattern; }
}
