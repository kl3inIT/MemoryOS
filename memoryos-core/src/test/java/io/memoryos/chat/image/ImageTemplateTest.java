package io.memoryos.chat.image;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.chat.image.ImageTemplate.Format;
import io.memoryos.chat.image.ImageTemplate.Step;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ImageTemplateTest {
    private static final Step GOOD = step("/v1/images/generations", "{\"prompt\":\"{{prompt}}\",\"model\":\"{{model}}\"}");

    private static ImageTemplate template(String header, String prefix, Step generate, @Nullable Step edit) {
        return new ImageTemplate(header, prefix, generate, edit);
    }
    private static Step step(String path, String body) { return new Step(path, body, "/data/0/b64_json", Format.BASE64); }

    @Test
    void aWellFormedTemplateValidatesAndRoundTripsThroughJson() {
        var edit = step("/v1/images/edits", "{\"prompt\":\"{{prompt}}\",\"image\":\"{{image_data_uri}}\"}");
        var valid = template("Authorization", "Bearer ", GOOD, edit);
        assertDoesNotThrow(valid::validate);
        assertEquals(valid, ImageTemplate.fromJson(valid.toJson()));
    }

    @Test
    void pathsStayBelowTheEndpoint() {
        for (var path : new String[]{"//evil.example/x", "https://evil.example/x", "/a/../b", "/a/./b", "/a?x=1", "/a#f",
                "a/b", "/a b", "/a//b", ""})
            assertThrows(IllegalArgumentException.class, () -> template("Authorization", "", step(path, "{}"), null).validate(), path);
    }

    @Test
    void theKeyHeaderCannotSplitHeadersAndBodiesMustBeJsonObjectsWithKnownVariables() {
        assertThrows(IllegalArgumentException.class, () -> template("Authorization\r\nX", "", GOOD, null).validate());
        assertThrows(IllegalArgumentException.class, () -> template("Authorization", "Bearer\r\n", GOOD, null).validate());
        assertThrows(IllegalArgumentException.class, () -> template("Authorization", "", step("/g", "not json"), null).validate());
        assertThrows(IllegalArgumentException.class, () -> template("Authorization", "", step("/g", "[1]"), null).validate());
        assertThrows(IllegalArgumentException.class,
                () -> template("Authorization", "", step("/g", "{\"k\":\"{{api_key}}\"}"), null).validate());
        // The working image exists only for an edit.
        assertThrows(IllegalArgumentException.class,
                () -> template("Authorization", "", step("/g", "{\"i\":\"{{image_base64}}\"}"), null).validate());
        assertThrows(IllegalArgumentException.class,
                () -> template("Authorization", "", new Step("/g", "{}", "data/0", Format.BASE64), null).validate());
        assertDoesNotThrow(() -> template("Authorization", "", new Step("/g", "{}", "", Format.RAW), null).validate());
    }
}
