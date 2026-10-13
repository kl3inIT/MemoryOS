package io.memoryos.channel;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** One answer being written into a chat. It ends with {@link #finish} or {@link #fail}, never both. */
public interface ChannelReply {
    /** More of the answer's text, as it is written. An adapter that cannot show partial text ignores it. */
    void append(String text);

    /**
     * The whole answer as it was saved, its sources and a closing line.
     *
     * @return the provider's id of the answer's message, by which a reply to it is recognised; null when unknown
     */
    @Nullable String finish(String text, List<Source> sources, @Nullable String footer);

    /** The answer could not be written; the notice says so in its place. */
    void fail(String notice);

    /** A source of the answer, with the address it opens at when it has one. */
    record Source(String title, @Nullable String url) {}
}
