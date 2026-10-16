/**
 * OCR: the Tenant's connections to services that read scans and images, and the one the extraction pipeline reads
 * with. The pipeline's adapters implement {@link io.memoryos.ai.ocr.OcrProbe} for the connection test.
 */
@NamedInterface("ocr")
@NullMarked
package io.memoryos.ai.ocr;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
