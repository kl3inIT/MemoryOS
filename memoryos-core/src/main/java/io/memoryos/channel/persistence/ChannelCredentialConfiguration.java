package io.memoryos.channel.persistence;

import io.memoryos.channel.ChannelException;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.shared.CredentialCipher;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bots keep their own encryption key, apart from the connectors' (MEM-246): a leaked connector key opens no bot's
 * secret, and a missing key fails the bots alone. Each provider is a credential kind of its own in the envelope.
 */
@Component
public final class ChannelCredentialConfiguration {
    private final String encodedKey;
    private final String keyVersion;
    private final Map<ChannelProvider, CredentialCipher> ciphers = new EnumMap<>(ChannelProvider.class);

    public ChannelCredentialConfiguration(
            @Value("${memoryos.channel.credential-encryption-key:}") String encodedKey,
            @Value("${memoryos.channel.credential-key-version:}") String keyVersion) {
        this.encodedKey = encodedKey;
        this.keyVersion = keyVersion;
    }

    public synchronized CredentialCipher cipher(ChannelProvider provider) {
        var existing = ciphers.get(provider);
        if (existing != null) return existing;
        byte[] key = null;
        try {
            if (keyVersion.isBlank() || keyVersion.length() > 64) throw ChannelException.notConfigured();
            key = Base64.getDecoder().decode(encodedKey);
            var cipher = new CredentialCipher(key, keyVersion, "CHANNEL_" + provider.name());
            ciphers.put(provider, cipher);
            return cipher;
        } catch (IllegalArgumentException exception) {
            throw ChannelException.notConfigured();
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
