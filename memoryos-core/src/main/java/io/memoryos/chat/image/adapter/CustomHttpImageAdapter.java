package io.memoryos.chat.image.adapter;

import static io.memoryos.chat.image.ImageCall.base64;
import static io.memoryos.chat.image.ImageCall.fromDataUri;
import static io.memoryos.chat.image.ImageCall.mediaType;
import static io.memoryos.chat.image.ImageCall.pngDataUri;

import io.memoryos.chat.image.ImageCall;
import io.memoryos.chat.image.ImageEditImages;
import io.memoryos.chat.image.ImageGenerationAdapter;
import io.memoryos.chat.image.ImageProvider;
import io.memoryos.chat.image.ImageProviderCapabilities;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.chat.image.ImageTemplate;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Any synchronous image API a manager describes with an {@link ImageTemplate}. Variables are substituted into the
 * parsed body tree, never into its text, so a prompt cannot add members or change the request's shape; a whole-value
 * variable without a value removes its member. Requests go only to the connection's endpoint, and a hosted image URL
 * in an answer is a failure, never fetched.
 */
@Component
public final class CustomHttpImageAdapter implements ImageGenerationAdapter {
    private static final ImageProviderCapabilities CAPABILITIES = new ImageProviderCapabilities(null, true, null, List.of());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Override public ImageProvider provider() { return ImageProvider.CUSTOM_HTTP; }
    @Override public ImageProviderCapabilities capabilities() { return CAPABILITIES; }
    @Override public String resolvedModel(String model) { return model; }

    /** Without a catalog, a tool shape maps straight to its aspect ratio. */
    @Override public @Nullable String size(String model, @Nullable String shape) {
        if (shape == null) return null;
        return switch (shape.toLowerCase(Locale.ROOT)) {
            case "square" -> "1:1";
            case "landscape" -> "16:9";
            case "portrait" -> "9:16";
            default -> null;
        };
    }

    @Override public ImageProviderClient.Result generate(ImageCall call, String base, String key, String model, String prompt,
                                                         @Nullable String size, @Nullable ImageTemplate template) throws IOException {
        var shapes = required(template);
        var values = new HashMap<String, @Nullable String>();
        values.put("prompt", prompt);
        values.put("model", model);
        values.put("aspect_ratio", size);
        return run(call, base, key, shapes, shapes.generate(), values);
    }

    @Override public ImageProviderClient.Result edit(ImageCall call, String base, String key, String model, String prompt,
                                                     ImageEditImages.Working image, @Nullable ImageTemplate template) throws IOException {
        var shapes = required(template);
        if (shapes.edit() == null) throw new IOException("This image connection does not edit images");
        var values = new HashMap<String, @Nullable String>();
        values.put("prompt", prompt);
        values.put("model", model);
        values.put("aspect_ratio", null);
        values.put("image_data_uri", pngDataUri(image.png()));
        values.put("image_base64", Base64.getEncoder().encodeToString(image.png()));
        return run(call, base, key, shapes, shapes.edit(), values);
    }

    private static ImageTemplate required(@Nullable ImageTemplate template) throws IOException {
        if (template == null) throw new IOException("Custom image connection has no template");
        return template;
    }

    private static ImageProviderClient.Result run(ImageCall call, String base, String key, ImageTemplate template,
                                                  ImageTemplate.Step step, Map<String, @Nullable String> values) throws IOException {
        String url = base + step.path();
        // The template is validated on save; the destination is re-checked so no request leaves the endpoint's host.
        if (!Objects.equals(URI.create(url).getHost(), URI.create(base).getHost()))
            throw new IOException("Image template leaves the endpoint");
        var headers = Map.of(template.authHeader(), template.authPrefix() + key);
        var body = Objects.requireNonNull(substitute(JSON.readTree(step.body()), values));
        byte[] bytes = switch (step.imageFormat()) {
            case RAW -> call.raw(url, headers, body);
            case BASE64 -> base64(call.json(url, headers, body).at(step.imagePointer()).asString(""));
            case DATA_URI -> fromDataUri(call.json(url, headers, body).at(step.imagePointer()).asString(""));
        };
        return new ImageProviderClient.Result(bytes, mediaType(bytes), null);
    }

    /** A copy of the node with each string's variables replaced; null marks a member or element to drop. */
    private static @Nullable JsonNode substitute(JsonNode node, Map<String, @Nullable String> values) {
        if (node.isObject()) {
            ObjectNode copy = NODES.objectNode();
            for (var member : node.properties()) {
                var value = substitute(member.getValue(), values);
                if (value != null) copy.set(member.getKey(), value);
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = NODES.arrayNode();
            for (var element : node) {
                var value = substitute(element, values);
                if (value != null) copy.add(value);
            }
            return copy;
        }
        if (!node.isString()) return node;
        String text = node.asString();
        var whole = ImageTemplate.VARIABLE.matcher(text);
        if (whole.matches()) {
            String value = values.get(whole.group(1));
            return value == null ? null : NODES.stringNode(value);
        }
        var parts = ImageTemplate.VARIABLE.matcher(text);
        return NODES.stringNode(parts.replaceAll(found -> Matcher.quoteReplacement(
                Objects.requireNonNullElse(values.get(found.group(1)), ""))));
    }
}
