package io.memoryos.chat.image;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;

/**
 * What an image provider needs and the models its adapter serves end to end. The catalog prefills administration; a
 * connection may still name another model.
 *
 * @param defaultEndpoint  used when a connection leaves its endpoint empty; null when the manager must supply one
 * @param endpointRequired the manager must configure an endpoint
 * @param editModel        the model every edit uses; null when edits use the connection's configured model
 * @param knownModels      generation models, newest first
 */
@NamedInterface("image")
public record ImageProviderCapabilities(@Nullable String defaultEndpoint, boolean endpointRequired,
                                        @Nullable KnownModel editModel, List<KnownModel> knownModels) {
    public ImageProviderCapabilities {
        if (knownModels.isEmpty() || knownModels.stream().map(KnownModel::modelName).distinct().count() != knownModels.size())
            throw new IllegalArgumentException("Invalid image model catalog");
        knownModels = List.copyOf(knownModels);
    }

    /** Every image protocol MemoryOS implements authenticates its requests. */
    public boolean requiresKey() { return true; }

    /** Resolves a tool shape through the catalog; a model outside the catalog ignores the shape. */
    public @Nullable String sizeFor(String model, @Nullable String shape) {
        var known = knownModels.stream().filter(entry -> entry.modelName().equals(model)).findFirst().orElse(null);
        return known == null ? null : known.sizeFor(shape);
    }

    /** Published metadata only: runtime behaviour follows the connection's stored model, never this entry. */
    public record KnownModel(String modelName, String displayName, String outputMediaType, List<String> sizes,
                             boolean edit, boolean deprecated) {
        public static final List<String> GPT_IMAGE_SIZES = List.of("1024x1024", "1536x1024", "1024x1536");
        private static final Pattern SIZE = Pattern.compile("[1-9][0-9]{1,4}x[1-9][0-9]{1,4}");
        private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

        public KnownModel {
            if (modelName == null || modelName.isBlank() || modelName.length() > 200
                    || displayName == null || displayName.isBlank()
                    || outputMediaType == null || !MEDIA_TYPES.contains(outputMediaType)
                    || sizes == null || !sizes.stream().allMatch(size -> size != null && SIZE.matcher(size).matches()))
                throw new IllegalArgumentException("Invalid known image model metadata");
            sizes = List.copyOf(sizes);
        }

        /** The declared size matching a tool shape; null for an unknown shape or an empty size list. */
        public @Nullable String sizeFor(@Nullable String shape) {
            if (shape == null) return null;
            Integer aspect = switch (shape.toLowerCase(Locale.ROOT)) {
                case "square" -> 0;
                case "landscape" -> 1;
                case "portrait" -> -1;
                default -> null;
            };
            if (aspect == null) return null;
            for (var size : sizes) {
                var at = size.indexOf('x');
                int order = Integer.compare(
                        Integer.parseInt(size.substring(0, at)), Integer.parseInt(size.substring(at + 1)));
                if (order == aspect) return size;
            }
            return null;
        }
    }
}
