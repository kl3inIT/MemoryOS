package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatSpreadsheetSheet")
public record ChatSpreadsheetSheetResponse(String name, String csv, boolean truncated) {}
