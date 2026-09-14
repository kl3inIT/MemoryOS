package io.memoryos.api.chat;

import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ChatProviderAdapter.TokenizerProfile;
import io.memoryos.chat.catalog.ModelSettings;
import java.util.List;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** Composition-owned hosted estimator. Catalog metadata never initializes the vocabulary. */
final class ChatTokenizerProfiles {
    static final String HOSTED = "openai-o200k-v1";
    static final List<TokenizerProfile> METADATA = List.of(
            new TokenizerProfile(HOSTED, "OpenAI O200K (hosted baseline)"));

    private ChatTokenizerProfiles() {}

    private static final class Hosted {
        private static final TokenCountEstimator TOKENS = new JTokkitTokenCountEstimator(EncodingType.O200K_BASE);
    }

    static TokenCountEstimator hostedTokens() { return Hosted.TOKENS; }

    static void validate(ModelSettings settings) {
        if (!HOSTED.equals(settings.tokenizerProfile())) throw ChatException.invalid("Unsupported tokenizer profile.");
    }
}
