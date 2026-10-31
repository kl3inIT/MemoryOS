package io.memoryos.meeting;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the name a voice gave itself. Meetings open with people saying who they are — "Chào mọi người, mình là Minh" —
 * and the owner then types that same name into the speaker they are already listening to. The sentence is enough to
 * offer it, with the line it came from, for the owner to accept or dismiss; nothing here renames anybody, and no model
 * is asked, so an offer costs a meeting nothing.
 */
final class SpeakerIntroductions {
    /** A name said plainly. A participant of the meeting saying it leaves no real doubt. */
    private static final double SAID = 0.9;
    private static final double EXPECTED = 0.97;
    /** A name is one to four capitalized words; longer is a sentence that happens to start with one. */
    private static final String NAME = "(\\p{Lu}[\\p{L}]*(?:\\s+\\p{Lu}[\\p{L}]*){0,3})";
    /** The words around a name are read whatever their case, because a sentence capitalizes its first word. */
    private static final String I = "(?i:tôi|mình|em|anh|chị|tớ|con|cháu)";
    private static final List<Pattern> INTRODUCTIONS = List.of(
            // "tôi là Minh", "mình tên là Anh Minh", "em tên Lan"
            Pattern.compile("\\b" + I + "\\s+(?i:tên\\s+)?(?i:là\\s+)?" + NAME),
            // "tên tôi là Minh", "tên mình Minh"
            Pattern.compile("(?i:\\btên)\\s+" + I + "\\s+(?i:là\\s+)?" + NAME),
            Pattern.compile("\\b(?i:I\\s+am|I'm|my\\s+name\\s+is|this\\s+is)\\s+" + NAME));
    /**
     * "Minh đây", "Minh xin phép". Every sentence opens with a capital, so "Chạy đây" reads the same as a name does;
     * the words are taken for one only when the meeting expects somebody called that.
     */
    private static final Pattern ANNOUNCEMENT = Pattern.compile("^" + NAME + "\\s+(?i:đây|xin\\s+phép)\\b");
    /**
     * Words that follow "tôi là" without being a name: a role, a company, a sentence carrying on. Written without
     * marks, because the comparison drops them.
     */
    private static final Set<String> NOT_A_NAME = Set.of("nguoi", "ai", "gi", "mot", "nhan", "vien", "truong", "phong",
            "giam", "doc", "ban", "be", "sinh", "hoc", "ke", "toan", "thu", "ky", "chu", "tri", "khach", "moi", "day",
            "nay", "the", "cua", "va", "co", "khong", "duoc", "cong", "ty", "bo", "phan", "dai", "dien", "nhom", "xin",
            "chao", "vang", "roi", "thi", "se", "dang", "phai", "can", "muon", "biet", "nghi", "thay", "lam");

    private SpeakerIntroductions() {}

    /**
     * What each unnamed voice called itself, at the earliest line where it said so. A voice the owner already named, or
     * whose offer they dismissed, is left alone.
     */
    static Map<String, Meeting.SpeakerSuggestion> suggest(List<Meeting.Utterance> utterances, List<String> participants,
            List<Meeting.Speaker> asking) {
        var wanted = asking.stream().map(speaker -> SpeakerNames.key(speaker.track(), speaker.label())).collect(Collectors.toSet());
        var found = new LinkedHashMap<String, Meeting.SpeakerSuggestion>();
        var expected = participants.stream().map(SpeakerIntroductions::plain).filter(name -> !name.isBlank()).toList();
        for (var utterance : utterances) {
            String key = SpeakerNames.key(utterance.track(), utterance.speaker());
            if (!wanted.contains(key) || found.containsKey(key)) continue;
            said(utterance.text(), expected).ifPresent(name -> found.put(key, new Meeting.SpeakerSuggestion(name, utterance.id(),
                    expected.contains(plain(name)) ? EXPECTED : SAID)));
        }
        return found;
    }

    private static Optional<String> said(String text, List<String> expected) {
        for (var sentence : text.split("(?<=[.!?])\\s+")) {
            for (var pattern : INTRODUCTIONS) {
                Matcher matcher = pattern.matcher(sentence.strip());
                if (matcher.find()) {
                    String name = trim(matcher.group(1));
                    if (!name.isBlank()) return Optional.of(name);
                }
            }
            Matcher announced = ANNOUNCEMENT.matcher(sentence.strip());
            if (announced.find()) {
                String name = trim(announced.group(1));
                if (!name.isBlank() && awaited(expected, plain(name))) return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /** Whether a participant goes by this name: the whole of it, or the given name a Vietnamese full name ends with. */
    private static boolean awaited(List<String> expected, String name) {
        return expected.stream().anyMatch(participant -> participant.equals(name) || participant.endsWith(" " + name));
    }

    /** Keeps the leading words that can be a name and drops the rest of the sentence the pattern swept up. */
    private static String trim(String candidate) {
        var kept = new ArrayList<String>();
        for (var word : candidate.strip().split("\\s+")) {
            if (NOT_A_NAME.contains(plain(word))) break;
            kept.add(word);
        }
        return String.join(" ", kept);
    }

    /** Lower case without marks, so "Minh" answers to "minh" and a participant list written plainly still matches. */
    private static String plain(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }
}
