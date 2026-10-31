package io.memoryos.chat.interpreter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.modulith.NamedInterface;
import io.memoryos.chat.ChatException;
import io.memoryos.sandbox.SandboxCommand;
import io.memoryos.sandbox.SandboxException;
import io.memoryos.sandbox.SandboxService;
import io.memoryos.shared.ActorId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PDF preview of a generated presentation, converted by LibreOffice in a sandbox of no conversation on first request
 * and cached beside the file, as Onyx Craft converts decks inside its sandbox (MEM-271). The API never parses the deck.
 */
@Service
@NamedInterface("interpreter")
public class PresentationPreviewService {
    /** pptx-to-pdf stops LibreOffice at 45 s; the command gets a little more. */
    static final Duration TIMEOUT = Duration.ofSeconds(60);
    static final String DECK = SandboxService.WORKSPACE + "/deck.pptx";
    static final String PDF = SandboxService.WORKSPACE + "/preview.pdf";
    /** The largest preview kept, as a generated file. */
    static final long MAX_PDF_BYTES = 25L * 1024 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(PresentationPreviewService.class);

    private final InterpreterService files;
    private final SandboxService sandboxes;

    public PresentationPreviewService(InterpreterService files, SandboxService sandboxes) {
        this.files = files;
        this.sandboxes = sandboxes;
    }

    public InterpreterService.Served pdf(ActorId actor, UUID id) {
        var artifact = files.presentation(actor, id);
        var name = artifact.filename().replaceAll("(?i)\\.pptx$", "") + ".pdf";
        if (artifact.previewKey() != null)
            return new InterpreterService.Served(files.openObject(artifact.previewKey()), name, "application/pdf");
        var tenant = files.activeTenant(actor);
        if (!files.enabled(tenant)) throw ChatException.providerUnavailable();
        byte[] pdf = convert(actor, id);
        files.storePreview(tenant, id, pdf);
        var stored = files.presentation(actor, id);
        if (stored.previewKey() == null) throw ChatException.unavailable();
        return new InterpreterService.Served(files.openObject(stored.previewKey()), name, "application/pdf");
    }

    private byte[] convert(ActorId actor, UUID id) {
        var tenant = files.activeTenant(actor);
        try {
            byte[] pdf = sandboxes.scratch(tenant, sandbox -> {
                try (var content = files.open(actor, id).content()) {
                    sandbox.write(DECK, content.inputStream());
                }
                var run = sandbox.run(new SandboxCommand("pptx-to-pdf " + DECK + " " + PDF, SandboxService.WORKSPACE,
                        SandboxService.USER, TIMEOUT));
                if (run.timedOut() || run.exitCode() != 0) {
                    LOG.atWarn().addKeyValue("event", "chat.presentation_preview.conversion_failed")
                            .addKeyValue("timed_out", run.timedOut()).addKeyValue("exit_code", run.exitCode())
                            .log("Presentation preview conversion failed");
                    throw ChatException.invalid("The presentation could not be previewed");
                }
                return sandbox.read(PDF, MAX_PDF_BYTES);
            });
            if (pdf.length < 5 || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                throw ChatException.invalid("The presentation could not be previewed");
            return pdf;
        } catch (SandboxException refused) {
            if ("SANDBOX_BUSY".equals(refused.code())) throw ChatException.busy();
            LOG.atWarn().addKeyValue("event", "chat.presentation_preview.conversion_failed")
                    .addKeyValue("error_code", refused.code()).log("Presentation preview conversion failed");
            throw ChatException.providerUnavailable();
        } catch (IllegalArgumentException tooLarge) {
            throw ChatException.invalid("The presentation could not be previewed");
        }
    }
}
