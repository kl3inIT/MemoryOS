package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.*;

@Schema(name = "BranchSelection")
public record ChatBranchSelectionRequest(@NotNull UUID messageId, @Nullable UUID expectedChildId) {}
