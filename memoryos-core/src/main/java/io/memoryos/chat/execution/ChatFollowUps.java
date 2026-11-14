package io.memoryos.chat.execution;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** MEM-266: the follow-up questions a model wrote, one per line, as the chips under an answer show them. */
final class ChatFollowUps {
    static final int COUNT = 3;
    static final int MAX_LENGTH = 160;
    /** A list marker a model puts before a line: bullets, "1." or "1)", "Q1:". */
    private static final Pattern MARKER = Pattern.compile("^(?:[-*•·]+|\\(?\\d{1,2}[.):]|[Qq]\\d{1,2}[.):])\\s*");
    private static final Pattern QUOTES = Pattern.compile("^[\"'`“”‘’«»]+|[\"'`“”‘’«»]+$");

    private ChatFollowUps() {}

    static List<String> parse(String output) {
        var seen = new LinkedHashSet<String>();
        var questions = new ArrayList<String>();
        for (var raw : output.split("\\R")) {
            String line = raw.strip().replace("**", "");
            line = MARKER.matcher(line).replaceFirst("");
            line = QUOTES.matcher(line.strip()).replaceAll("").strip().replaceAll("\\s+", " ");
            if (line.isEmpty()) continue;
            if (line.codePointCount(0, line.length()) > MAX_LENGTH)
                line = line.substring(0, line.offsetByCodePoints(0, MAX_LENGTH - 1)).strip() + "…";
            if (!seen.add(line.toLowerCase(Locale.ROOT))) continue;
            questions.add(line);
            if (questions.size() == COUNT) break;
        }
        return List.copyOf(questions);
    }
}
