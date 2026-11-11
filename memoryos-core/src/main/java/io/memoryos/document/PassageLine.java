package io.memoryos.document;

import io.memoryos.document.ExtractedDocument.BoundingBox;
import java.util.Objects;

/**
 * One text line the OCR located in a block a passage cites: its page, its text and its box, whose {@code polygon} is
 * the line's quadrilateral, in the space and origin of the block's own box.
 */
public record PassageLine(int pageNo, String text, BoundingBox bbox) {
    public PassageLine {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(bbox, "bbox");
        if (pageNo < 1 || bbox.polygon() == null) throw new IllegalArgumentException("a line has a page and a quadrilateral");
    }
}
