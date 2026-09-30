package io.memoryos.ai;

import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.ai.ProviderAdapter.TokenizerProfile;
import java.util.List;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * The installed tokenizer profiles and the one estimator each resolves to. An adapter declares which profiles it
 * supports; a model's settings name one. Catalog metadata never initializes a vocabulary.
 */
public final class TokenizerProfiles {
    public static final String HOSTED = "openai-o200k-v1";
    public static final List<TokenizerProfile> HOSTED_METADATA = List.of(
            new TokenizerProfile(HOSTED, "OpenAI O200K (hosted baseline)"));

    private TokenizerProfiles() {}

    private static final class Hosted {
        private static final TokenCountEstimator TOKENS = new JTokkitTokenCountEstimator(EncodingType.O200K_BASE);
    }

    /** The baseline estimator, also used for a turn that has no selected model yet. */
    public static TokenCountEstimator hostedTokens() { return Hosted.TOKENS; }

    /** The estimator a profile resolves to; an unknown profile is a configuration error. */
    public static TokenCountEstimator estimator(String profile) {
        if (!HOSTED.equals(profile)) throw AiException.invalid("Unsupported tokenizer profile.");
        return hostedTokens();
    }
}
