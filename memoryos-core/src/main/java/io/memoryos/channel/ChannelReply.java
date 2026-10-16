package io.memoryos.channel;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** One answer being written into a chat. It ends with {@link #finish} or {@link #fail}, never both. */
public interface ChannelReply {
    /**
     * More of the answer's text, as it is written. Called only for a provider whose {@link ChannelFeatures#editsReply}
     * is true.
     */
    void append(String text);

    /**
     * The whole answer as it was saved, its sources and a closing line. A provider that edits its reply gets the answer
     * as one piece; one that does not gets it cut by {@link ChannelText#pieces} at its {@link ChannelFeatures#maxText},
     * each piece a message, the sources and the closing line after the last.
     *
     * @return the provider's id of the answer's last message, by which a reply to it is recognised; null when unknown
     * @throws Incomplete when some of the answer's messages were sent and a later one was not
     */
    @Nullable String finish(List<String> pieces, List<Source> sources, @Nullable String footer);

    /** The answer could not be written; the notice says so in its place. */
    void fail(String notice);

    /** A source of the answer, with the address it opens at when it has one. */
    record Source(String title, @Nullable String url) {}

    /**
     * Part of an answer sent in several messages reached the chat and the rest did not. It carries no provider text.
     *
     * @param lastSent the provider's id of the last message that was sent, null when unknown
     */
    final class Incomplete extends RuntimeException {
        private final @Nullable String lastSent;

        public Incomplete(@Nullable String lastSent) {
            super("The answer was sent in part", null, false, false);
            this.lastSent = lastSent;
        }

        public @Nullable String lastSent() {
            return lastSent;
        }
    }
}
