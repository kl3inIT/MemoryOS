package io.memoryos.api.ocr.contract;

import io.memoryos.ai.ocr.OcrProvider;
import org.jspecify.annotations.Nullable;

/** The connection that reads scans and images; null returns them to Docling's own OCR. */
public record OcrSelectionRequest(@Nullable OcrProvider provider) {}
