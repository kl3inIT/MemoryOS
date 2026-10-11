package io.memoryos.ai.ocr;

import io.memoryos.ai.DataBoundary;

/** A service that reads scans and images, one adapter each in the extraction pipeline. */
public enum OcrProvider {
    /** PaddleOCR-VL's layout-parsing service, self-hosted on a GPU; reachable by network, without a key (MEM-192). */
    PADDLEOCR_VL(false, DataBoundary.INTERNAL);

    private final boolean requiresKey;
    private final DataBoundary dataBoundary;

    OcrProvider(boolean requiresKey, DataBoundary dataBoundary) {
        this.requiresKey = requiresKey;
        this.dataBoundary = dataBoundary;
    }

    public boolean requiresKey() { return requiresKey; }

    /** Where a new connection's data goes unless the administrator says otherwise. */
    public DataBoundary dataBoundary() { return dataBoundary; }
}
