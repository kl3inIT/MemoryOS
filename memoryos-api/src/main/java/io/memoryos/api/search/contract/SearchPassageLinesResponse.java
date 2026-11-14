package io.memoryos.api.search.contract;

import io.memoryos.document.PassageLine;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The text lines the OCR located in the blocks one passage cites, so a reader of a scan marks the line that holds
 * the question rather than the whole block. Empty for a passage read without located lines: a PDF with a text
 * layer, a scan read before lines were located, or another OCR reader.
 */
@Schema(name = "SearchPassageLines")
public record SearchPassageLinesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Line> lines) {
    public static SearchPassageLinesResponse from(List<PassageLine> lines) {
        return new SearchPassageLinesResponse(lines.stream().map(line -> new Line(line.pageNo(), line.text(),
                line.bbox().coordOrigin().name(), line.bbox().frameWidth(), line.bbox().frameHeight(),
                line.bbox().polygon())).toList());
    }

    /**
     * One line, in the space of its block's box: PDF user space in points with y up ({@code BOTTOMLEFT}), or a frame
     * of {@code frameWidth} by {@code frameHeight} from its top left ({@code TOPLEFT}), as the passage's provenance is.
     */
    @Schema(name = "SearchPassageLine")
    public record Line(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pageNo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"TOPLEFT", "BOTTOMLEFT"})
            String coordOrigin,
            @Nullable Double frameWidth,
            @Nullable Double frameHeight,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The line's quadrilateral as [x, y] corners, in the order the OCR answered them")
            List<List<Double>> points) { }
}
