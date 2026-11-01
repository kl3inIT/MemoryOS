package io.memoryos.api.chat.contract;

import io.memoryos.chat.image.ImageTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** A custom HTTP image connection's request shapes; the key travels only in {@code authHeader}. */
@Schema(name = "ImageTemplate")
public record ImageTemplateBody(@NotNull @Size(max = 64) String authHeader, @NotNull @Size(max = 32) String authPrefix,
        @NotNull @Valid ImageTemplateStepBody generate, @Nullable @Valid ImageTemplateStepBody edit) {
    public ImageTemplate toTemplate() {
        return new ImageTemplate(authHeader, authPrefix, generate.toStep(), edit == null ? null : edit.toStep());
    }

    public static @Nullable ImageTemplateBody from(@Nullable ImageTemplate template) {
        if (template == null) return null;
        var edit = template.edit();
        return new ImageTemplateBody(template.authHeader(), template.authPrefix(), ImageTemplateStepBody.from(template.generate()),
                edit == null ? null : ImageTemplateStepBody.from(edit));
    }
}
