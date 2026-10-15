package io.memoryos.integration.zalo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An answer's markdown as Zalo shows it. Seen on a real bot (MEM-263 step 1), Zalo's markdown renders bold, italic and
 * lists, shows a {@code [text](url)} link as typed, makes a bare address tappable and drops backticks; what it does with
 * headings, tables, quotes and code fences is not documented. So what it renders is kept and the rest becomes text it
 * reads well: a link its text and address, an image its description and address, a heading a bold line, a table one
 * {@code header: value} line a cell, a quote and a code block their text. OpenClaw and GoClaw send Zalo plain text and
 * strip or convert the same constructs.
 */
final class ZaloText {
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```|~~~).*$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern AUTOLINK = Pattern.compile("<((?:https?|mailto):[^\\s<>]+)>");
    private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");

    private ZaloText() {
    }

    static String plain(String markdown) {
        var lines = markdown.replace("\r\n", "\n").split("\n", -1);
        var out = new ArrayList<String>();
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (FENCE.matcher(line).matches()) {
                fenced = !fenced;
                continue;
            }
            // A code block's lines are its content, untouched.
            if (fenced) {
                out.add(line);
                continue;
            }
            if (i + 1 < lines.length && line.contains("|") && TABLE_RULE.matcher(lines[i + 1]).matches()) {
                var header = cells(line);
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].isBlank()) {
                    var row = cells(lines[i]);
                    for (int c = 0; c < row.size(); c++) {
                        String name = c < header.size() ? inline(header.get(c)) : "";
                        String value = inline(row.get(c));
                        if (value.isEmpty()) continue;
                        out.add(name.isEmpty() ? value : name + ": " + value);
                    }
                    out.add("");
                    i++;
                }
                i--;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                out.add("");
                continue;
            }
            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                String text = inline(heading.group(1)).replace("**", "");
                out.add(text.isEmpty() ? "" : "**" + text + "**");
                continue;
            }
            var quote = QUOTE.matcher(line);
            while (quote.lookingAt()) {
                line = line.substring(quote.end());
                quote = QUOTE.matcher(line);
            }
            out.add(inline(line));
        }
        return BLANK_RUN.matcher(String.join("\n", out)).replaceAll("\n\n").strip();
    }

    /** A table row's cells, without its outer pipes. */
    private static List<String> cells(String row) {
        String inner = row.strip();
        if (inner.startsWith("|")) inner = inner.substring(1);
        if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
        var cells = new ArrayList<String>();
        for (String cell : inner.split("\\|", -1)) cells.add(cell.strip());
        return cells;
    }

    /** One line's inline markdown: links, images, autolinks and code spans. */
    static String inline(String line) {
        var text = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char at = line.charAt(i);
            if (at == '`') {
                int close = line.indexOf('`', i + 1);
                if (close > i) {
                    text.append(line, i + 1, close);
                    i = close + 1;
                    continue;
                }
            }
            boolean image = at == '!' && i + 1 < line.length() && line.charAt(i + 1) == '[';
            if (at == '[' || image) {
                var link = link(line, image ? i + 1 : i);
                if (link != null) {
                    text.append(link.render(image));
                    i = link.end();
                    continue;
                }
            }
            text.append(at);
            i++;
        }
        return AUTOLINK.matcher(text).replaceAll("$1");
    }

    /** A {@code [label](destination "title")} at {@code open}, its destination's parentheses balanced; null for none. */
    private static @Nullable Link link(String line, int open) {
        int close = line.indexOf(']', open + 1);
        if (close < 0 || close + 1 >= line.length() || line.charAt(close + 1) != '(') return null;
        int depth = 1;
        int j = close + 2;
        while (j < line.length() && depth > 0) {
            char c = line.charAt(j);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth > 0) j++;
        }
        if (depth != 0) return null;
        String inside = line.substring(close + 2, j).strip();
        int space = inside.indexOf(' ');
        String destination = (space < 0 ? inside : inside.substring(0, space)).replaceAll("^<|>$", "");
        if (destination.isEmpty()) return null;
        return new Link(line.substring(open + 1, close).strip(), destination, j + 1);
    }

    private record Link(String label, String destination, int end) {
        String render(boolean image) {
            if (label.isEmpty()) return image ? "" : destination;
            if (label.equals(destination)) return destination;
            return label + " (" + destination + ")";
        }
    }
}
