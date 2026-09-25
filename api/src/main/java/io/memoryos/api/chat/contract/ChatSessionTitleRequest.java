package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@Schema(name = "Title")
public record ChatSessionTitleRequest(@NotBlank @Size(max = 200) String title) {}
