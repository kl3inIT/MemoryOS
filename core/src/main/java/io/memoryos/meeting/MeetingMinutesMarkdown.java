package io.memoryos.meeting;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The minutes as a document Chat can read: the summary, the decisions and the work the meeting handed out.
 *
 * <p>The transcript is deliberately absent. A transcript is the raw thing people said, and the Delaware Chancery
 * court in ATG Capital v. Lane read one back against the minutes it contradicted; counsel's advice since is that the
 * approved minutes are the record and the transcript is working material kept close. So what reaches the knowledge
 * base is what the owner read and let stand, and the transcript stays on the meeting page where its readers are.
 */
public final class MeetingMinutesMarkdown {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);
    /** Long enough for a day of decisions, short enough to stay inside the Chat upload limit. */
    private static final int MAX_CHARS = 200_000;

    private MeetingMinutesMarkdown() {}

    /** A file name a person recognizes in their library, derived from the meeting's own title. */
    public static String filename(Meeting.Detail meeting) {
        String name = meeting.title().replaceAll("[\\\\/:*?\"<>|\\r\\n]", " ").strip();
        if (name.length() > 120) name = name.substring(0, 120).strip();
        return (name.isEmpty() ? "Cuoc hop" : name) + ".md";
    }

    public static byte[] render(Meeting.Detail meeting) {
        var out = new StringBuilder();
        out.append("# ").append(meeting.title()).append("\n\n");
        out.append("Cuộc họp ").append(WHEN.format(meeting.createdAt()));
        if (meeting.endedAt() != null) out.append(" – ").append(WHEN.format(meeting.endedAt()));
        out.append('\n');
        if (!meeting.minutes().kind().isBlank()) out.append("Loại: ").append(meeting.minutes().kind()).append('\n');
        if (!meeting.participants().isEmpty())
            out.append("Thành phần: ").append(String.join(", ", meeting.participants())).append('\n');
        out.append('\n');

        if (!meeting.minutes().summary().isBlank())
            out.append("## Tóm tắt\n\n").append(meeting.minutes().summary().strip()).append("\n\n");

        if (!meeting.minutes().decisions().isEmpty()) {
            out.append("## Quyết định\n\n");
            for (var decision : meeting.minutes().decisions()) item(out, decision);
            out.append('\n');
        }
        if (!meeting.minutes().actions().isEmpty()) {
            out.append("## Việc cần làm\n\n");
            for (var action : meeting.minutes().actions()) item(out, action);
            out.append('\n');
        }
        // Said plainly, so a reader of the file knows the full wording lives somewhere else.
        out.append("---\n\nBiên bản này do MemoryOS viết từ bản ghi cuộc họp. Transcript đầy đủ ở trang cuộc họp.\n");
        String text = out.length() > MAX_CHARS ? out.substring(0, MAX_CHARS) : out.toString();
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void item(StringBuilder out, Meeting.MinutesItem item) {
        out.append("- ").append(item.text());
        if (item.owner() != null) out.append(" — ").append(item.owner());
        if (item.due() != null) out.append(" (hạn: ").append(item.due()).append(')');
        out.append('\n');
        if (item.quote() != null && !item.quote().isBlank())
            out.append("  > ").append(item.quote().strip().replace("\n", " ")).append('\n');
    }
}
