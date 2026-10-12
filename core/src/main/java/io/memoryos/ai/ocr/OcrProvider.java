package io.memoryos.ai.ocr;

import io.memoryos.ai.DataBoundary;
import org.jspecify.annotations.Nullable;

/** What can read scans and images, one adapter each in the extraction pipeline. */
public enum OcrProvider {
    /** PaddleOCR-VL's layout-parsing service, self-hosted on a GPU; reachable by network, without a key (MEM-192). */
    PADDLEOCR_VL(false, false, DataBoundary.INTERNAL, null),
    /**
     * A catalog model that reads images, one page picture a call. Its connection names the model; the address, the key
     * and the data boundary are the model's provider's.
     */
    MODEL(false, true, DataBoundary.EXTERNAL, null),
    /** AI Hay's OCR API, a cloud service billed by the call: one page picture in, the page's Markdown out. */
    AIHAY(true, false, DataBoundary.EXTERNAL, "https://api.ai-hay.vn");

    private final boolean requiresKey;
    private final boolean usesModel;
    private final DataBoundary dataBoundary;
    private final @Nullable String defaultEndpoint;

    OcrProvider(boolean requiresKey, boolean usesModel, DataBoundary dataBoundary, @Nullable String defaultEndpoint) {
        this.requiresKey = requiresKey;
        this.usesModel = usesModel;
        this.dataBoundary = dataBoundary;
        this.defaultEndpoint = defaultEndpoint;
    }

    public boolean requiresKey() { return requiresKey; }

    /** Whether a connection names a catalog model instead of a service address. */
    public boolean usesModel() { return usesModel; }

    /** Where a new connection's data goes unless the administrator, or its model's provider, says otherwise. */
    public DataBoundary dataBoundary() { return dataBoundary; }

    /** The public address of a cloud service, offered when a connection is made; null for a service one hosts. */
    public @Nullable String defaultEndpoint() { return defaultEndpoint; }
}
