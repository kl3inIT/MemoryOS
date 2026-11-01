package io.memoryos.channel;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a provider says of a group chat: whether people outside the bot's organisation read it, its name, null when the
 * provider gives none (Zalo), and, for a provider without organisations that lists a group's people (WhatsApp), who is
 * in it besides the bot; null when the provider cannot tell (MEM-285).
 */
public record ChannelChat(boolean external, @Nullable String name, @Nullable List<Person> participants) {
    /** A chat of a provider without organisations or names. */
    public static final ChannelChat UNNAMED = new ChannelChat(false, null);

    public ChannelChat {
        participants = participants == null ? null : List.copyOf(participants);
    }

    /** A chat whose people the provider does not list. */
    public ChannelChat(boolean external, @Nullable String name) {
        this(external, name, null);
    }

    /** One person in a group, by every id the provider knows them by, any of which a link may name. */
    public record Person(List<String> ids) {
        public Person {
            ids = List.copyOf(ids);
        }
    }
}
