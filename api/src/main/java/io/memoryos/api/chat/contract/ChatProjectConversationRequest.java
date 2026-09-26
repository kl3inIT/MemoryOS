package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "ProjectConversation")
public record ChatProjectConversationRequest(@NotBlank @Size(max = 200) String title) {}
