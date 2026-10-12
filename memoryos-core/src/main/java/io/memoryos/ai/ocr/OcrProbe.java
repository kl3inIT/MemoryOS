package io.memoryos.ai.ocr;

/**
 * Reads a small picture with a known line of text on one connection; the extraction pipeline, which holds the
 * adapters, implements it.
 */
public interface OcrProbe {
    /**
     * Whether the connection read the picture's text. False covers every failure: a refused key, a service that cannot
     * be reached or answers otherwise, and a reading without the text; none of the service's own words is kept.
     *
     * @param key the connection's plaintext key, empty when it has none
     */
    boolean reads(OcrConnectionService.Connection connection, String key);
}
