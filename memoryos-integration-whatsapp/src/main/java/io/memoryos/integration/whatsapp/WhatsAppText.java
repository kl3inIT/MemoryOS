package io.memoryos.integration.whatsapp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * An answer's markdown as WhatsApp formats it (MEM-283). WhatsApp has its own marks: {@code *bold*}, {@code _italic_},
 * {@code ~strike~}, {@code `code`} and {@code ```} blocks, {@code - } and {@code 1. } lists and {@code > } quotes; it
 * has no links, headings or tables. So a heading becomes a bold line, a link its text and address, an image its
 * description and address, and a table one {@code header: value} line a cell, as Zalo's conversion does. The markdown
 * is parsed by commonmark-java with the GitHub tables and strikethrough, so emphasis inside words and nested lists come
 * out as the model meant them.
 */
final class WhatsAppText {
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create())).build();
    private static final Pattern BLANK_RUN = Pattern.compile("\\n{3,}");

    private WhatsAppText() {
    }

    static String of(String markdown) {
        var out = new StringBuilder();
        blocks(PARSER.parse(markdown.replace("\r\n", "\n")), out);
        return BLANK_RUN.matcher(out.toString()).replaceAll("\n\n").strip();
    }

    /** Each block of {@code parent}, a blank line between them. */
    private static void blocks(Node parent, StringBuilder out) {
        for (Node block = parent.getFirstChild(); block != null; block = block.getNext()) {
            block(block, out);
            out.append("\n\n");
        }
    }

    private static void block(Node block, StringBuilder out) {
        switch (block) {
            case Heading heading -> {
                String text = inline(heading).strip();
                if (!text.isEmpty()) out.append('*').append(text.replace("*", "")).append('*');
            }
            case Paragraph paragraph -> out.append(inline(paragraph));
            case BulletList list -> items(list, false, out);
            case OrderedList list -> items(list, true, out);
            case BlockQuote quote -> {
                var inner = new StringBuilder();
                blocks(quote, inner);
                for (String line : inner.toString().strip().split("\n", -1)) {
                    out.append(line.isEmpty() ? ">" : "> " + line).append('\n');
                }
            }
            case FencedCodeBlock code -> out.append("```\n").append(stripEnd(code.getLiteral())).append("\n```");
            case IndentedCodeBlock code -> out.append("```\n").append(stripEnd(code.getLiteral())).append("\n```");
            case HtmlBlock html -> out.append(stripEnd(html.getLiteral()));
            case ThematicBreak ignored -> { }
            case TableBlock table -> table(table, out);
            default -> blocks(block, out);
        }
    }

    private static void items(Node list, boolean ordered, StringBuilder out) {
        int number = list instanceof OrderedList numbered && numbered.getMarkerStartNumber() != null
                ? numbered.getMarkerStartNumber() : 1;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) continue;
            var inner = new StringBuilder();
            blocks(item, inner);
            String marker = ordered ? number++ + ". " : "- ";
            var lines = inner.toString().strip().split("\n", -1);
            out.append(marker).append(lines[0]).append('\n');
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isBlank()) out.append("  ").append(lines[i]).append('\n');
            }
        }
    }

    /** A table as {@code header: value} lines, a blank line after each row. */
    private static void table(TableBlock table, StringBuilder out) {
        var header = new ArrayList<String>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                var cells = new ArrayList<String>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell) cells.add(inline(cell).strip());
                }
                if (section instanceof TableHead) {
                    header.addAll(cells);
                } else if (section instanceof TableBody) {
                    for (int c = 0; c < cells.size(); c++) {
                        if (cells.get(c).isEmpty()) continue;
                        String name = c < header.size() ? header.get(c) : "";
                        out.append(name.isEmpty() ? cells.get(c) : name + ": " + cells.get(c)).append('\n');
                    }
                    out.append('\n');
                }
            }
        }
    }

    private static String inline(Node parent) {
        var out = new StringBuilder();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) inline(node, out);
        return out.toString();
    }

    private static void inline(Node node, StringBuilder out) {
        switch (node) {
            case Text text -> out.append(text.getLiteral());
            case StrongEmphasis strong -> wrap(strong, "*", out);
            case Emphasis emphasis -> wrap(emphasis, "_", out);
            case Strikethrough strike -> wrap(strike, "~", out);
            case Code code -> out.append('`').append(code.getLiteral()).append('`');
            case Link link -> {
                String label = inline(link).strip();
                String address = link.getDestination();
                if (label.isEmpty() || label.equals(address)) out.append(address);
                else out.append(label).append(" (").append(address).append(')');
            }
            case Image image -> {
                String label = inline(image).strip();
                out.append(label.isEmpty() ? image.getDestination() : label + " (" + image.getDestination() + ")");
            }
            case SoftLineBreak ignored -> out.append('\n');
            case HardLineBreak ignored -> out.append('\n');
            case HtmlInline html -> out.append(html.getLiteral());
            default -> out.append(inline(node));
        }
    }

    /** A mark around text, outside its spaces, since WhatsApp formats only a mark that touches a word. */
    private static void wrap(Node node, String mark, StringBuilder out) {
        String text = inline(node);
        String inner = text.strip();
        if (inner.isEmpty()) {
            out.append(text);
            return;
        }
        int lead = text.indexOf(inner);
        out.append(text, 0, lead).append(mark).append(inner).append(mark).append(text.substring(lead + inner.length()));
    }

    private static String stripEnd(String text) {
        return text.stripTrailing();
    }
}
