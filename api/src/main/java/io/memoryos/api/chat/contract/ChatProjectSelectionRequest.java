package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.*;

@Schema(name = "ProjectSelection")
public record ChatProjectSelectionRequest(@Nullable UUID projectId) {}
