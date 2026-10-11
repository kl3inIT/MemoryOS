package io.memoryos.ingestion.extraction;

import io.memoryos.ai.AiException;
import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrPageReader;
import io.memoryos.ai.ocr.OcrProvider;
import io.memoryos.document.ExtractionFailure;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** A catalog model that reads images: one page picture in, the page's text out. */
final class ModelOcrAdapter implements OcrAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(ModelOcrAdapter.class);
    /** Providers refuse or resize a picture beyond a few megabytes. */
    private static final int MAX_PICTURE_BYTES = 4_194_304;
    private final OcrPageReader pages;
    private final ObjectMapper mapper;

    ModelOcrAdapter(OcrPageReader pages, ObjectMapper mapper) {
        this.pages = pages;
        this.mapper = mapper;
    }

    @Override public OcrProvider provider() { return OcrProvider.MODEL; }

    /**
     * The model's own provider holds the address and the key; the connection only names the model. A model that is gone
     * or no longer reads images is the connection's configuration; any other failure is the provider's, which did not
     * answer, so the document may be read again once it does.
     */
    @Override public OcrReader reader(OcrConnectionService.Connection connection, String key) {
        return new PagePictureOcrReader((picture, mediaType) -> {
            try {
                return pages.read(connection, picture, mediaType);
            } catch (AiException configuration) {
                LOG.atWarn().addKeyValue("event", "model_ocr.page.refused").addKeyValue("error_code", configuration.code())
                        .log("The OCR connection's model cannot read a page");
                throw DocumentAssembly.failure(ExtractionFailure.INTERNAL);
            } catch (RuntimeException provider) {
                LOG.atWarn().addKeyValue("event", "model_ocr.page.failed")
                        .addKeyValue("error_type", provider.getClass().getName())
                        .log("The OCR connection's model did not read a page");
                throw DocumentAssembly.failure(ExtractionFailure.CONNECTION_FAILED);
            }
        }, new PagePictureOcrReader.Settings("model-ocr", String.valueOf(connection.modelConfigurationId()),
                Set.of("image/png", "image/jpeg", "image/webp"), MAX_PICTURE_BYTES), mapper);
    }
}
