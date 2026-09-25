package io.memoryos.chat.interpreter;

import java.nio.charset.StandardCharsets;
import org.springframework.modulith.NamedInterface;
import io.memoryos.chat.ChatException;
import io.memoryos.shared.ActorId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Service;

/**
 * PDF preview of a generated presentation, converted by LibreOffice inside the interpreter executor on first request
 * and cached beside the file, as Onyx Craft converts decks inside its sandbox. The API never parses the deck.
 */
@Service
@NamedInterface("interpreter")
public class PresentationPreviewService {
    /** The service caps timeout_ms at MAX_EXEC_TIMEOUT_MS (60 s); pptx-to-pdf itself stops LibreOffice at 45 s. */
    static final int TIMEOUT_MS = 60_000;
    static final String DECK = "deck.pptx";
    static final String PDF = "preview.pdf";
    static final String CODE = """
            import subprocess, sys
            sys.exit(subprocess.run(['pptx-to-pdf', 'deck.pptx', 'preview.pdf']).returncode)""";
    private static final Logger LOG = LoggerFactory.getLogger(PresentationPreviewService.class);

    private final InterpreterService files;
    private final InterpreterClient client;

    public PresentationPreviewService(InterpreterService files, InterpreterClient client) {
        this.files = files;
        this.client = client;
    }

    public InterpreterService.Served pdf(ActorId actor, UUID id) {
        var artifact = files.presentation(actor, id);
        var name = artifact.filename().replaceAll("(?i)\\.pptx$", "") + ".pdf";
        if (artifact.previewKey() != null)
            return new InterpreterService.Served(files.openObject(artifact.previewKey()), name, "application/pdf");
        var tenant = files.activeTenant(actor);
        if (!client.configured() || !files.enabled(tenant) || !client.healthy()) throw ChatException.providerUnavailable();
        byte[] pdf = convert(actor, id);
        files.storePreview(tenant, id, pdf);
        var stored = files.presentation(actor, id);
        if (stored.previewKey() == null) throw ChatException.unavailable();
        return new InterpreterService.Served(files.openObject(stored.previewKey()), name, "application/pdf");
    }

    private byte[] convert(ActorId actor, UUID id) {
        String deck = null;
        String output = null;
        try {
            try (var content = files.open(actor, id).content()) {
                deck = client.upload(DECK, InterpreterService.PPTX, content.inputStream());
            }
            var execution = client.execute(CODE, TIMEOUT_MS, List.of(new InterpreterClient.StagedFile(DECK, deck)));
            for (var file : execution.files())
                if (PDF.equals(file.path()) && file.fileId() != null) output = file.fileId();
                else if ("file".equals(file.kind()) && file.fileId() != null && !file.fileId().equals(deck)) delete(file.fileId());
            if (execution.timedOut() || execution.exitCode() == null || execution.exitCode() != 0 || output == null) {
                LoggingEventBuilder log = LOG.atWarn().addKeyValue("event", "chat.presentation_preview.conversion_failed")
                        .addKeyValue("timed_out", execution.timedOut());
                if (execution.exitCode() != null) log = log.addKeyValue("exit_code", execution.exitCode());
                log.log("Presentation preview conversion failed");
                throw ChatException.invalid("The presentation could not be previewed");
            }
            byte[] pdf = client.download(output);
            if (pdf.length < 5 || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                throw ChatException.invalid("The presentation could not be previewed");
            return pdf;
        } catch (InterpreterClient.BusyException busy) {
            throw ChatException.busy();
        } catch (IOException failure) {
            LOG.atWarn().addKeyValue("event", "chat.presentation_preview.conversion_failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Presentation preview conversion failed");
            throw ChatException.providerUnavailable();
        } finally {
            if (deck != null) delete(deck);
            if (output != null) delete(output);
        }
    }

    private void delete(String fileId) {
        try { client.delete(fileId); }
        catch (IOException | RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "chat.presentation_preview.delete_failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Code Interpreter could not delete a preview file");
        }
    }
}
