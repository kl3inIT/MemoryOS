package io.memoryos.ai.ocr;

import com.embabel.common.ai.model.LlmOptions;
import io.memoryos.ai.AiException;
import io.memoryos.ai.ModelLeases;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

/**
 * Reads the text of one page picture with the catalog model an OCR connection names. One call a page, without tools or
 * thinking, through the provider adapter and its request policy, so every provider a model can come from reads a page
 * the same way. The call is bounded by the page's output limit and the provider read timeout; its usage is recorded
 * as {@link AiUsageFlow#OCR}.
 */
@Service
public class OcrPageReader {
    private static final Logger LOG = LoggerFactory.getLogger(OcrPageReader.class);
    /** A dense page of text and tables stays well under this; it bounds a model that would not stop. */
    static final int MAX_OUTPUT_TOKENS = 8192;
    static final String INSTRUCTIONS = """
            You transcribe one page of a scanned document. Return every piece of text on the page exactly as written, \
            in its original language and reading order, with nothing added, translated, corrected or summarized. \
            Write a table as a Markdown table and keep each number exactly as printed. Do not describe pictures, \
            logos, stamps or signatures. If the page holds no text, return nothing at all.""";
    private static final String REQUEST = "Transcribe this page.";

    private final OcrConnectionService connections;
    private final ModelLeases leases;
    private final @Nullable AiUsageRecorder usage;

    public OcrPageReader(OcrConnectionService connections, ModelLeases leases, ObjectProvider<AiUsageRecorder> usage) {
        this.connections = connections;
        this.leases = leases;
        this.usage = usage.getIfAvailable();
    }

    /**
     * The text of one page picture; empty when the model finds none.
     *
     * @throws AiException when the connection's model is gone, no longer reads images, or its provider is disabled
     * @throws RuntimeException when the provider call fails; its message may name the address and is never shown
     */
    public String read(OcrConnectionService.Connection connection, byte[] picture, String mediaType) {
        var selection = connections.model(connection);
        try (var lease = leases.lease(selection.provider(), selection.model())) {
            var binding = lease.binding();
            if (!binding.vision()) throw AiException.invalid("The model does not read images.");
            int output = binding.outputAtMost(MAX_OUTPUT_TOKENS);
            var options = binding.service().convertOptions(new LlmOptions().withMaxTokens(output).withoutThinking());
            var page = UserMessage.builder().text(REQUEST)
                    .media(new Media(MimeType.valueOf(mediaType), new ByteArrayResource(picture))).build();
            ChatResponse response = binding.service().getChatModel()
                    .call(binding.policy().request(new Prompt(List.of(new SystemMessage(INSTRUCTIONS), page), options), output));
            record(connection, selection, response);
            var result = response.getResult();
            String text = result == null ? null : result.getOutput().getText();
            return text == null ? "" : text.strip();
        }
    }

    private void record(OcrConnectionService.Connection connection, OcrConnectionService.ModelSelection selection,
                        ChatResponse response) {
        if (usage == null) return;
        try {
            var reported = response.getMetadata().getUsage();
            long input = reported.getPromptTokens() == null ? 0 : reported.getPromptTokens();
            long output = reported.getCompletionTokens() == null ? 0 : reported.getCompletionTokens();
            long cached = reported.getCacheReadInputTokens() == null ? 0 : reported.getCacheReadInputTokens();
            var pricing = selection.model().settings().pricing();
            Double cost = pricing == null ? null : ((input - cached) * pricing.inputPerMillion()
                    + cached * pricing.cachedInputRate() + output * pricing.outputPerMillion()) / 1_000_000;
            var provider = selection.provider();
            usage.record(AiUsage.tokens(connection.tenantId(), null, AiUsageFlow.OCR, provider.name(),
                    selection.model().modelName(), provider.id(), selection.model().id(), provider.dataBoundary().name(),
                    input, output, cached, cost, input + output > 0, Instant.now()));
        } catch (RuntimeException failure) {
            // The page was read; a ledger that refuses the entry must not take the reading with it.
            LOG.atWarn().addKeyValue("event", "ocr.page.usage_not_recorded")
                    .addKeyValue("error_type", failure.getClass().getName()).log("OCR page usage not recorded");
        }
    }
}
