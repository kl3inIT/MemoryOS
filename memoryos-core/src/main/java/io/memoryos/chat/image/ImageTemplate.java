package io.memoryos.chat.image;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A custom HTTP image connection's request shapes: where each request goes under the connection's endpoint, the JSON
 * body with its variables, and where the image is in the answer. The key only ever travels in {@code authHeader}.
 *
 * @param edit null when the connection only generates
 */
@NamedInterface("image")
public record ImageTemplate(String authHeader, String authPrefix, Step generate, @Nullable Step edit) {
    public static final Set<String> GENERATE_VARIABLES = Set.of("prompt", "model", "aspect_ratio");
    public static final Set<String> EDIT_VARIABLES = Set.of("prompt", "model", "aspect_ratio", "image_data_uri", "image_base64");
    public static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-z0-9_]+)}}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");
    /** A path below the endpoint: no scheme, host, query, fragment, dot segment or empty segment. */
    private static final Pattern PATH = Pattern.compile("(/[A-Za-z0-9._~@+:%-]+)+/?");

    public enum Format { BASE64, DATA_URI, RAW }

    /** @param imagePointer RFC 6901 pointer to the image in a JSON answer; unused for {@link Format#RAW} */
    public record Step(String path, String body, String imagePointer, Format imageFormat) {}

    /** Throws {@link IllegalArgumentException} for a template that could leak the key, leave the endpoint or not parse. */
    public void validate() {
        if (authHeader == null || !HEADER.matcher(authHeader).matches()
                || authPrefix == null || authPrefix.length() > 32 || authPrefix.chars().anyMatch(c -> c < 0x20 || c == 0x7F)
                || generate == null)
            throw new IllegalArgumentException("Invalid image template");
        check(generate, GENERATE_VARIABLES);
        if (edit != null) check(edit, EDIT_VARIABLES);
    }

    private static void check(Step step, Set<String> variables) {
        if (step.path() == null || step.path().length() > 512 || !PATH.matcher(step.path()).matches()
                || step.path().contains("/./") || step.path().contains("/../") || step.path().endsWith("/..")
                || step.body() == null || step.body().length() > 16_384
                || step.imageFormat() == null || step.imagePointer() == null || step.imagePointer().length() > 200
                || step.imageFormat() != Format.RAW && !step.imagePointer().startsWith("/"))
            throw new IllegalArgumentException("Invalid image template");
        JsonNode body;
        try { body = JSON.readTree(step.body()); }
        catch (JacksonException invalid) { throw new IllegalArgumentException("Invalid image template body"); }
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Invalid image template body");
        Matcher found = VARIABLE.matcher(step.body());
        while (found.find())
            if (!variables.contains(found.group(1))) throw new IllegalArgumentException("Unknown image template variable");
    }

    public String toJson() { return JSON.writeValueAsString(this); }

    public static ImageTemplate fromJson(String json) { return JSON.readValue(json, ImageTemplate.class); }
}
