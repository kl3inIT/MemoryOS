package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatSpreadsheetPreview")
public record ChatSpreadsheetPreviewResponse(List<ChatSpreadsheetSheetResponse> sheets) {}
