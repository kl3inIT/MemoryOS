package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The search engines the gateway reports; empty when it has none connected. */
public record WebEnginesResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> engines) {
    public WebEnginesResponse { engines = List.copyOf(engines); }
}
