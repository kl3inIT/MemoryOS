package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LabelRequest")
public record ChatPersonaLabelRequest(String name) {}
