package io.memoryos.api.chat.contract;

import io.memoryos.chat.image.ImageTemplate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One request of a custom HTTP image connection: a path below the endpoint, a JSON body whose string values may hold
 * {@code {{prompt}}}, {@code {{model}}}, {@code {{aspect_ratio}}} and, for an edit, {@code {{image_data_uri}}} or
 * {@code {{image_base64}}}, and the RFC 6901 pointer to the image in the answer.
 */
@Schema(name = "ImageTemplateStep")
public record ImageTemplateStepBody(@NotNull @Size(max = 512) String path, @NotNull @Size(max = 16384) String body,
        @NotNull @Size(max = 200) String imagePointer, @NotNull ImageTemplate.Format imageFormat) {
    ImageTemplate.Step toStep() { return new ImageTemplate.Step(path, body, imagePointer, imageFormat); }

    static ImageTemplateStepBody from(ImageTemplate.Step step) {
        return new ImageTemplateStepBody(step.path(), step.body(), step.imagePointer(), step.imageFormat());
    }
}
