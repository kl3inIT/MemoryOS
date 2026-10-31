package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

@Schema(name = "UpdateGoogleDriveScheduleRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateGoogleDriveScheduleRequest(
        @NotNull @Min(1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "2147483647")
        @JsonDeserialize(using = IntervalDeserializer.class) Integer syncIntervalMinutes) {
    static final class IntervalDeserializer extends StdDeserializer<Integer> {
        IntervalDeserializer() { super(Integer.class); }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            return parser.getIntValue();
        }
    }
}
