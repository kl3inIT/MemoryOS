package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatLibraryTrashEmptied")
public record ChatLibraryTrashEmptiedResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int purged) {}
