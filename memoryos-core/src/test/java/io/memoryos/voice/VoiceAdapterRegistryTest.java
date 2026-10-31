package io.memoryos.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class VoiceAdapterRegistryTest {
    private final VoiceAdapterRegistry registry = VoiceAdapters.registry();

    @Test void everyProviderHasExactlyOneAdapterOrStartupFails() {
        var missing = new ArrayList<>(VoiceAdapters.all());
        missing.removeIf(adapter -> adapter.provider() == VoiceProvider.AZURE);
        assertEquals("No voice adapter for AZURE",
                assertThrows(IllegalStateException.class, () -> new VoiceAdapterRegistry(missing)).getMessage());
        var duplicate = new ArrayList<>(VoiceAdapters.all());
        duplicate.add(new SonioxVoiceAdapter());
        assertEquals("Two voice adapters for SONIOX",
                assertThrows(IllegalStateException.class, () -> new VoiceAdapterRegistry(duplicate)).getMessage());
    }

    @Test void functionsFollowTheImplementedProtocols() {
        assertEquals(EnumSet.complementOf(EnumSet.of(VoiceProvider.SONIOX)), matching(registry::speaks));
        assertEquals(EnumSet.of(VoiceProvider.OPENAI, VoiceProvider.OPENAI_COMPATIBLE, VoiceProvider.SONIOX),
                matching(registry::transcribesRecordings));
        assertEquals(EnumSet.of(VoiceProvider.SONIOX), matching(registry::diarizesRecordings));
        assertEquals(EnumSet.of(VoiceProvider.OPENAI, VoiceProvider.AZURE, VoiceProvider.SONIOX), matching(p -> registry.realtime(p).isPresent()));
        assertEquals(EnumSet.of(VoiceProvider.SONIOX), matching(p -> registry.live(p).isPresent()));
    }

    @Test void capabilitiesNameTheKeyAndEndpointEachProviderNeedsAndItsBaseUrl() {
        assertEquals(EnumSet.of(VoiceProvider.OPENAI_COMPATIBLE), matching(p -> !registry.capabilities(p).requiresKey()));
        assertEquals(EnumSet.of(VoiceProvider.OPENAI_COMPATIBLE, VoiceProvider.AZURE),
                matching(p -> registry.capabilities(p).requiresEndpoint()));
        assertEquals("https://api.soniox.com/v1", registry.capabilities(VoiceProvider.SONIOX).baseUrl(""));
        assertEquals("https://speech.example/v1", registry.capabilities(VoiceProvider.AZURE).baseUrl("https://speech.example/v1//"));
    }

    private static Set<VoiceProvider> matching(Predicate<VoiceProvider> test) {
        var providers = EnumSet.noneOf(VoiceProvider.class);
        for (var provider : VoiceProvider.values()) if (test.test(provider)) providers.add(provider);
        return providers;
    }
}
