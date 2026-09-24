package io.memoryos.meeting;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a biên bản says and in what order, following Nghị định 30/2020 mẫu 1.9, without knowing what it is drawn on.
 * The Word file and the PDF are both written from this, so the two can never say different things; each renderer only
 * decides how a line, an indent and a pair of columns look on its own page.
 */
final class MeetingMinutesLayout {
    /** Half-points, as Word counts them: the decree's 13-point body, its 14-point title and an 11-point caption. */
    static final int BODY = 26;
    static final int TITLE = 28;
    static final int SMALL = 22;
    static final String BLANK = "…";

    private MeetingMinutesLayout() {}

    enum Align { LEFT, CENTER, JUSTIFY }

    /** One line of a column: the letterhead and the signature block are two of these side by side. */
    record Cell(String text, boolean bold, int size) {}

    /** Where a biên bản is written. */
    interface Page {
        void line(String text, boolean bold, int size, Align align);

        /** A body paragraph with the decree's first-line indent. */
        void indented(String text);

        /** One item under a numbered section. */
        void listed(String text);

        void bullet(String text);

        void blank();

        /**
         * Two centred columns side by side. {@code keepWithPrevious} asks that they never open a page on their own:
         * a signature block alone on its last page signs nothing that anyone can see.
         */
        void columns(List<Cell> left, List<Cell> right, boolean keepWithPrevious);
    }

    static void write(Meeting.Detail meeting, MeetingMinutesDocument.Heading heading, Page page) {
        letterhead(heading, page);
        title(heading, page);
        opening(heading, page);
        attendees(heading, page);
        content(meeting, heading, page);
        signatures(heading, page);
    }

    /** The two-column letterhead: the body on the left, the national heading on the right. */
    private static void letterhead(MeetingMinutesDocument.Heading heading, Page page) {
        page.columns(
                List.of(new Cell(or(heading.parentOrganization(), ""), true, BODY),
                        new Cell(or(heading.organization(), BLANK), true, BODY),
                        new Cell("Số: " + or(heading.number(), BLANK) + "/BB", false, BODY)),
                List.of(new Cell("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", true, BODY),
                        new Cell("Độc lập - Tự do - Hạnh phúc", true, BODY)),
                false);
    }

    private static void title(MeetingMinutesDocument.Heading heading, Page page) {
        page.blank();
        page.line("BIÊN BẢN", true, TITLE, Align.CENTER);
        page.line("Về việc " + or(heading.about(), BLANK), true, BODY, Align.CENTER);
        page.blank();
    }

    /**
     * Labelled lines flush with the numbered sections, as the decree's biên bản form writes them. The subject is
     * already under the title, so it is not said again here.
     */
    private static void opening(MeetingMinutesDocument.Heading heading, Page page) {
        page.line("Thời gian bắt đầu: " + or(heading.opened(), BLANK), false, BODY, Align.LEFT);
        page.line("Địa điểm: " + or(heading.place(), BLANK), false, BODY, Align.LEFT);
        page.blank();
    }

    private static void attendees(MeetingMinutesDocument.Heading heading, Page page) {
        page.line("I. Thành phần tham dự:", true, BODY, Align.LEFT);
        page.listed("1. Chủ trì: " + person(heading.chair(), heading.chairRole()));
        page.listed("2. Thư ký: " + person(heading.secretary(), heading.secretaryRole()));
        page.listed("3. Thành phần khác:");
        if (heading.attendees().isEmpty()) page.bullet(BLANK);
        else heading.attendees().forEach(page::bullet);
        page.blank();
    }

    private static void content(Meeting.Detail meeting, MeetingMinutesDocument.Heading heading, Page page) {
        var minutes = meeting.minutes();
        page.line("II. Nội dung cuộc họp:", true, BODY, Align.LEFT);
        paragraphs(minutes.summary(), page);
        page.blank();

        page.line("III. Kết luận cuộc họp:", true, BODY, Align.LEFT);
        if (minutes.decisions().isEmpty()) page.listed(BLANK);
        else numbered(minutes.decisions().stream().map(Meeting.MinutesItem::text).toList(), page);
        page.blank();

        page.line("IV. Nhiệm vụ được giao:", true, BODY, Align.LEFT);
        if (minutes.actions().isEmpty()) page.listed(BLANK);
        else numbered(minutes.actions().stream().map(MeetingMinutesLayout::assignment).toList(), page);
        page.blank();

        page.indented("Cuộc họp kết thúc vào lúc " + or(heading.closed(), BLANK)
                + ", nội dung cuộc họp đã được các thành viên dự họp thông qua và cùng ký vào biên bản./.");
        page.blank();
    }

    /** "Kiểm tra bảng cân đối do Anh Minh thực hiện, thời hạn thứ Tư." */
    private static String assignment(Meeting.MinutesItem action) {
        var sentence = new StringBuilder(action.text());
        if (action.owner() != null) sentence.append(" do ").append(action.owner()).append(" thực hiện");
        if (action.due() != null) sentence.append(", thời hạn ").append(action.due());
        if (sentence.charAt(sentence.length() - 1) != '.') sentence.append('.');
        return sentence.toString();
    }

    private static void signatures(MeetingMinutesDocument.Heading heading, Page page) {
        page.columns(signature("THƯ KÝ", heading.secretary()), signature("CHỦ TỌA", heading.chair()), true);
    }

    private static List<Cell> signature(String role, @Nullable String name) {
        return List.of(new Cell(role, true, BODY), new Cell("(Ký, ghi rõ họ tên)", false, SMALL),
                new Cell("", false, BODY), new Cell("", false, BODY), new Cell(or(name, BLANK), true, BODY));
    }

    private static String person(@Nullable String name, @Nullable String role) {
        String who = "Ông/Bà " + or(name, BLANK);
        return isBlank(role) ? who : who + " - Chức vụ: " + role.strip();
    }

    private static void paragraphs(String text, Page page) {
        if (isBlank(text)) {
            page.listed(BLANK);
            return;
        }
        text.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(page::listed);
    }

    private static void numbered(List<String> items, Page page) {
        int position = 1;
        for (var item : items) page.listed(position++ + ". " + item);
    }

    private static String or(@Nullable String value, String fallback) {
        return isBlank(value) ? fallback : value.strip();
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
