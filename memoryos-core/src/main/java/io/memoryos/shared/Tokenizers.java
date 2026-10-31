package io.memoryos.shared;

import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/**
 * One token counter per BPE vocabulary for the whole process. Each {@link JTokkitTokenCountEstimator} loads its own
 * copy of the vocabulary, tens of megabytes of maps; a counter per service or per test once filled a 1.5 GB heap with
 * 59 copies (MEM-199). The counters are thread-safe and load on first use.
 */
public final class Tokenizers {
    private Tokenizers() {}

    private static final class O200k {
        private static final TokenCountEstimator TOKENS = new JTokkitTokenCountEstimator(EncodingType.O200K_BASE);
    }

    private static final class Cl100k {
        private static final TokenCountEstimator TOKENS = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);
    }

    /** OpenAI's o200k vocabulary: the hosted chat models' token count. */
    public static TokenCountEstimator o200k() { return O200k.TOKENS; }

    /** OpenAI's cl100k vocabulary: the document chunk and embedding input bounds. */
    public static TokenCountEstimator cl100k() { return Cl100k.TOKENS; }
}
