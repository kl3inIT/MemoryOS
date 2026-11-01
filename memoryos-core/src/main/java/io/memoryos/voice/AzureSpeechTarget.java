package io.memoryos.voice;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Where the Speech SDK connects, derived from the endpoint an administrator stored for REST. A resource with a custom
 * domain ({@code https://name.cognitiveservices.azure.com}) or a self-hosted container is used as the endpoint; the
 * regional form the portal shows without a custom domain ({@code https://southeastasia.api.cognitive.microsoft.com})
 * becomes its region, which the SDK takes with the key. Exactly one of the two is set.
 */
record AzureSpeechTarget(@Nullable URI endpoint, @Nullable String region) {
    private static final Pattern REGIONAL = Pattern.compile("([a-z0-9]+)\\.api\\.cognitive\\.microsoft\\.com");

    AzureSpeechTarget {
        if ((endpoint == null) == (region == null)) throw new IllegalArgumentException("Exactly one of endpoint or region");
    }

    static AzureSpeechTarget of(String baseUrl) {
        var uri = URI.create(baseUrl);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        var regional = REGIONAL.matcher(host);
        return regional.matches() ? new AzureSpeechTarget(null, regional.group(1)) : new AzureSpeechTarget(uri, null);
    }
}
