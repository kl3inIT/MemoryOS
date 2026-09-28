package io.memoryos.api.search.contract;

import io.memoryos.document.SpreadsheetPreview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A workbook original as CSV text per sheet, in workbook order. A workbook never reaches the browser as
 * bytes, so this is the shape both the Search reader and a Chat citation render.
 */
@Schema(name = "DocumentSpreadsheet")
public record DocumentSpreadsheetResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Sheet> sheets) {
    public static DocumentSpreadsheetResponse from(List<SpreadsheetPreview.Sheet> sheets) {
        return new DocumentSpreadsheetResponse(
                sheets.stream().map(sheet -> new Sheet(sheet.name(), sheet.csv(), sheet.truncated())).toList());
    }

    @Schema(name = "DocumentSpreadsheetSheet")
    public record Sheet(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String csv,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean truncated) { }
}
