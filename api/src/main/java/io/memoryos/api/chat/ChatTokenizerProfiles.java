package io.memoryos.api.chat;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.knuddels.jtokkit.api.EncodingType;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ChatProviderAdapter.TokenizerProfile;
import io.memoryos.chat.catalog.ModelSettings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

/** Composition-owned installed assets. Catalog metadata never initializes either vocabulary. */
final class ChatTokenizerProfiles implements AutoCloseable {
    static final String HOSTED = "openai-o200k-v1";
    static final String SMOL = "smollm2-135m-12fd25f-v1";
    static final List<TokenizerProfile> METADATA = List.of(
            new TokenizerProfile(HOSTED, "OpenAI O200K (hosted baseline)"),
            new TokenizerProfile(SMOL, "SmolLM2 135M (12fd25f)"));
    private static final String ASSETS = "/io/memoryos/api/chat/tokenizers/" + SMOL + "/";
    private HuggingFaceTokenizer nativeTokenizer;
    private TokenCountEstimator nativeTokens;
    private int references;
    private boolean closed;

    private static final class Hosted {
        private static final TokenCountEstimator TOKENS = new JTokkitTokenCountEstimator(EncodingType.O200K_BASE);
    }

    static TokenCountEstimator hostedTokens() { return Hosted.TOKENS; }

    static void validate(ModelSettings settings) {
        if (HOSTED.equals(settings.tokenizerProfile())) return;
        if (!SMOL.equals(settings.tokenizerProfile())) throw ChatException.invalid("Unsupported tokenizer profile.");
        var capabilities = settings.capabilities();
        if (capabilities.toolCalling() || capabilities.vision() || capabilities.reasoning())
            throw ChatException.invalid("The installed tokenizer profile supports text-only Chat without tools or reasoning.");
    }

    synchronized Lease acquire(String profile) {
        if (closed) throw ChatException.providerUnavailable();
        if (HOSTED.equals(profile)) return new Lease(hostedTokens(), () -> {});
        if (!SMOL.equals(profile)) throw ChatException.invalid("Unsupported tokenizer profile.");
        if (nativeTokenizer == null) {
            configureOfflineCpu();
            try {
                byte[] tokenizer = verified("tokenizer.json", "9ca9acddb6525a194ec8ac7a87f24fbba7232a9a15ffa1af0c1224fcd888e47c");
                verified("tokenizer_config.json", "4ec77d44f62efeb38d7e044a1db318f6a939438425312dfa333b8382dbad98df");
                verified("chat-template.jinja", "872be49dbb638044ad01b60388f48d469ff2980e5f0dccdc22ec907db54d0788");
                nativeTokenizer = HuggingFaceTokenizer.newInstance(new ByteArrayInputStream(tokenizer),
                        Map.of("addSpecialTokens", "false", "padding", "false", "truncation", "false"));
                nativeTokens = new NativeTokens(nativeTokenizer);
            } catch (IOException failure) {
                throw new IllegalStateException("Installed tokenizer assets are unavailable", failure);
            }
        }
        references++;
        return new Lease(nativeTokens, this::release);
    }

    private synchronized void release() {
        references--;
        if (closed && references == 0) dispose();
    }

    @Override public synchronized void close() {
        closed = true;
        if (references == 0) dispose();
    }

    private void dispose() {
        if (nativeTokenizer != null) {
            nativeTokenizer.close();
            nativeTokenizer = null;
            nativeTokens = null;
        }
    }

    private static void configureOfflineCpu() {
        requireEnvironment("RUST_FLAVOR", "cpu");
        requireEnvironment("DJL_OFFLINE", "true");
        requireEnvironment("OPT_OUT_TRACKING", "true");
        System.setProperty("RUST_FLAVOR", "cpu");
        System.setProperty("ai.djl.offline", "true");
        System.setProperty("OPT_OUT_TRACKING", "true");
        if (System.getenv("DJL_CACHE_DIR") == null)
            System.setProperty("DJL_CACHE_DIR", Path.of(System.getProperty("java.io.tmpdir"), "memoryos-tokenizers").toString());
        ChatTokenizerNativeLibrary.prepare();
    }

    private static void requireEnvironment(String name, String expected) {
        String configured = System.getenv(name);
        if (configured != null && !expected.equals(configured))
            throw new IllegalStateException("Unsupported tokenizer runtime configuration: " + name);
    }

    private static byte[] verified(String name, String checksum) throws IOException {
        try (var input = ChatTokenizerProfiles.class.getResourceAsStream(ASSETS + name)) {
            if (input == null) throw new IOException("Missing installed tokenizer asset: " + name);
            byte[] bytes = input.readAllBytes();
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(checksum))
                throw new IOException("Invalid installed tokenizer asset: " + name);
            return bytes;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static final class Lease implements AutoCloseable {
        private final TokenCountEstimator tokens;
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();
        Lease(TokenCountEstimator tokens, Runnable release) { this.tokens = tokens; this.release = release; }
        TokenCountEstimator tokens() { return tokens; }
        @Override public void close() { if (closed.compareAndSet(false, true)) release.run(); }
    }

    private record NativeTokens(HuggingFaceTokenizer tokenizer) implements TokenCountEstimator {
        @Override public int estimate(@Nullable String text) {
            return text == null || text.isEmpty() ? 0 : tokenizer.encode(text, false, false).getIds().length;
        }
        @Override public int estimate(MediaContent content) {
            if (!content.getMedia().isEmpty()) throw ChatException.invalid("The tokenizer profile does not support media.");
            return estimate(content.getText());
        }
        @Override public int estimate(Iterable<MediaContent> messages) {
            int count = 0;
            for (var message : messages) count = Math.addExact(count, estimate(message));
            return count;
        }
    }
}
