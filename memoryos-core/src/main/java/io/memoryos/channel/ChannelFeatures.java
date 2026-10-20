package io.memoryos.channel;

/**
 * What a provider's chats can do, as its adapter states it, so that the capability decides rather than the adapter
 * hiding it.
 *
 * @param organisations whether the provider names an organisation for each sender, by which a bot admits only its own
 * @param editsReply whether a reply can be rewritten while it is written; one that cannot gets the answer in pieces
 * @param maxText the longest text one message takes
 * @param pairs whether a bot is linked by scanning a code in the platform's app rather than given a secret (WhatsApp):
 *        it has no secret, and its transport opens into pairing until the code is scanned
 * @param groupMembers whether {@link ChannelAdapter#chat} lists a group's people (WhatsApp), so the capability can check
 *        each of them (MEM-285)
 */
public record ChannelFeatures(boolean organisations, boolean editsReply, int maxText, boolean pairs,
                              boolean groupMembers) {
    public ChannelFeatures {
        if (maxText < 1) throw new IllegalArgumentException("maxText must be positive");
    }

    /** A provider whose chats list no one. */
    public ChannelFeatures(boolean organisations, boolean editsReply, int maxText, boolean pairs) {
        this(organisations, editsReply, maxText, pairs, false);
    }

    /**
     * Whether a group is answered only once an administrator allows it (MEM-285): the provider can neither tell an
     * outsider by organisation nor list who is in the group, so nothing else keeps an outsider from reading an answer.
     */
    public boolean groupsNeedPermission() {
        return !organisations && !groupMembers;
    }
}
