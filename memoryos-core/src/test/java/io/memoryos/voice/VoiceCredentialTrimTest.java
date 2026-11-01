package io.memoryos.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.memoryos.ai.ProviderCredentials;
import org.junit.jupiter.api.Test;

/**
 * A key pasted from a clipboard brings whatever was around it. An HTTP header ignores a trailing newline, so the
 * saved-connection check passes; Soniox reads its key from a JSON field and answers {@code 401 unauthenticated}.
 * The result is a connection that verifies and then transcribes nothing, which is how live recording stayed silent
 * on staging while uploading a file worked.
 */
class VoiceCredentialTrimTest {
    private static VoiceConnectionService.Input input(ProviderCredentials.Action action, String value) {
        return new VoiceConnectionService.Input("", "stt-rt-v5", "", "",
                new ProviderCredentials.Change(action, value), null, 0);
    }

    @Test
    void stripsWhatTheClipboardBroughtWithTheKey() {
        var trimmed = VoiceConnectionService.trimmed(input(ProviderCredentials.Action.REPLACE, "  secret-key\n"));
        assertEquals("secret-key", trimmed.credential().value());
    }

    @Test
    void leavesACleanKeyExactlyAsItIs() {
        var clean = input(ProviderCredentials.Action.REPLACE, "secret-key");
        assertSame(clean, VoiceConnectionService.trimmed(clean), "a key that needs nothing is not rebuilt");
    }

    @Test
    void doesNotTouchAKeepOrARemove() {
        var keep = input(ProviderCredentials.Action.KEEP, null);
        assertSame(keep, VoiceConnectionService.trimmed(keep));
        var remove = input(ProviderCredentials.Action.REMOVE, null);
        assertSame(remove, VoiceConnectionService.trimmed(remove));
    }
}
