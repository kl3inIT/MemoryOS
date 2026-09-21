package io.memoryos.chat.application;

import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSession;
import java.time.format.DateTimeFormatter;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * How an exported conversation is written (MEM-153): one JSON file that keeps everything the transcript
 * recorded, and one HTML file a person can open in a browser years later without MemoryOS. The HTML escapes
 * every value it prints, because a transcript is text someone else may have written.
 */
final class ChatExportWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_INSTANT;

    private ChatExportWriter() {}

    /** The conversation as data: the selected path, with each message's citations, files and model record. */
    static byte[] json(ChatSession session, List<ChatMessage> messages) {
        ObjectNode root = JSON.createObjectNode();
        root.put("id", session.id().toString());
        root.put("title", session.title());
        root.put("createdAt", STAMP.format(session.createdAt()));
        root.put("updatedAt", STAMP.format(session.updatedAt()));
        root.put("archived", session.archived());
        if (session.projectId() != null) root.put("projectId", session.projectId().toString());
        if (session.branchedFromSessionId() != null)
            root.put("branchedFromSessionId", session.branchedFromSessionId().toString());
        ArrayNode written = root.putArray("messages");
        for (var message : messages) {
            ObjectNode node = written.addObject();
            node.put("id", message.id().toString());
            node.put("role", message.role().name());
            node.put("status", message.status().name());
            node.put("createdAt", STAMP.format(message.createdAt()));
            node.put("content", message.content() == null ? "" : message.content());
            ArrayNode files = node.putArray("files");
            for (var file : message.files()) {
                files.addObject().put("id", file.id().toString()).put("filename", file.filename())
                        .put("mediaType", file.mediaType()).put("sizeBytes", file.sizeBytes());
            }
            ArrayNode sources = node.putArray("sources");
            for (var source : message.sources()) {
                sources.addObject().put("citationId", source.citationId()).put("title", source.title());
            }
        }
        return root.toPrettyString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The conversation as a page: a question, an answer, what each answer cited and what it carried. */
    static byte[] html(ChatSession session, List<ChatMessage> messages) {
        var page = new StringBuilder();
        page.append(head(session.title()));
        page.append("<h1>").append(escape(session.title())).append("</h1>\n");
        page.append("<p class=\"meta\">").append(escape(STAMP.format(session.createdAt())));
        if (session.archived()) page.append(" · archived");
        page.append("</p>\n");
        for (var message : messages) {
            String role = switch (message.role()) {
                case USER -> "question";
                case ASSISTANT -> "answer";
                case ROOT -> "root";
            };
            if (message.role() == ChatMessage.Role.ROOT) continue;
            page.append("<section class=\"turn ").append(role).append("\">\n");
            page.append("<h2>").append(message.role() == ChatMessage.Role.USER ? "Question" : "Answer")
                    .append("</h2>\n");
            page.append("<p class=\"body\">").append(escape(message.content() == null ? "" : message.content()))
                    .append("</p>\n");
            if (!message.files().isEmpty()) {
                page.append("<p class=\"files\">Files: ");
                page.append(message.files().stream().map(file -> escape(file.filename()))
                        .collect(java.util.stream.Collectors.joining(", ")));
                page.append("</p>\n");
            }
            if (!message.sources().isEmpty()) {
                page.append("<ol class=\"sources\">\n");
                for (var source : message.sources()) {
                    page.append("<li>").append(escape(source.title())).append("</li>\n");
                }
                page.append("</ol>\n");
            }
            page.append("</section>\n");
        }
        page.append("</main></body></html>\n");
        return page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The export's own front page, so a ZIP of files is navigable rather than a heap. */
    static byte[] index(List<Entry> entries, List<String> skipped) {
        var page = new StringBuilder(head("MemoryOS export"));
        page.append("<h1>MemoryOS export</h1>\n<ul class=\"index\">\n");
        for (var entry : entries) {
            page.append("<li><a href=\"").append(escape(entry.href())).append("\">")
                    .append(escape(entry.title())).append("</a></li>\n");
        }
        page.append("</ul>\n");
        if (!skipped.isEmpty()) {
            page.append("<h2>Left out</h2>\n<ul class=\"skipped\">\n");
            for (var name : skipped) page.append("<li>").append(escape(name)).append("</li>\n");
            page.append("</ul>\n");
        }
        page.append("</main></body></html>\n");
        return page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    record Entry(String title, String href) {}

    private static String head(String title) {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>
                :root { color-scheme: light dark; }
                body { margin: 0 auto; padding: 2rem 1rem; max-width: 46rem;
                       font: 16px/1.6 ui-sans-serif, system-ui, sans-serif; }
                h1 { font-size: 1.5rem; } h2 { font-size: 0.8rem; text-transform: uppercase;
                       letter-spacing: 0.06em; opacity: 0.6; margin: 0 0 0.25rem; }
                .meta, .files { opacity: 0.6; font-size: 0.85rem; }
                .turn { border-top: 1px solid rgba(128,128,128,0.3); padding: 1rem 0; }
                .turn.question .body { font-weight: 600; }
                .body { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
                .sources { font-size: 0.85rem; opacity: 0.8; }
                .index { padding-left: 1rem; }
                </style></head><body><main>
                """.formatted(escape(title));
    }

    /** Every exported value is escaped: a transcript is text, and this page is opened outside MemoryOS. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
