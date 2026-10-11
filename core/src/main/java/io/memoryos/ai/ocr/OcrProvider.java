package io.memoryos.ai.ocr;

import io.memoryos.ai.DataBoundary;

/** What can read scans and images, one adapter each in the extraction pipeline. */
public enum OcrProvider {
    /** PaddleOCR-VL's layout-parsing service, self-hosted on a GPU; reachable by network, without a key (MEM-192). */
    PADDLEOCR_VL(false, false, DataBoundary.INTERNAL),
    /**
     * A catalog model that reads images, one page picture a call. Its connection names the model; the address, the key
     * and the data boundary are the model's provider's.
     */
    MODEL(false, true, DataBoundary.EXTERNAL);

    private final boolean requiresKey;
    private final boolean usesModel;
    private final DataBoundary dataBoundary;

    OcrProvider(boolean requiresKey, boolean usesModel, DataBoundary dataBoundary) {
        this.requiresKey = requiresKey;
        this.usesModel = usesModel;
        this.dataBoundary = dataBoundary;
    }

    public boolean requiresKey() { return requiresKey; }

    /** Whether a connection names a catalog model instead of a service address. */
    public boolean usesModel() { return usesModel; }

    /** Where a new connection's data goes unless the administrator, or its model's provider, says otherwise. */
    public DataBoundary dataBoundary() { return dataBoundary; }
}
