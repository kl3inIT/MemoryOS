package io.memoryos.chat.image;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Implemented image protocols and the models their adapters serve end to end; native model tools are not
 * image-provider connections. The catalog prefills administration; a connection may still name another model.
 */
public enum ImageProvider {
    OPENAI_IMAGE("https://api.openai.com/v1", false, null, List.of(
            new KnownModel("gpt-image-2", "GPT Image 2", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false),
            new KnownModel("gpt-image-1.5", "GPT Image 1.5", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false),
            new KnownModel("gpt-image-1", "GPT Image 1", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false))),
    /** Generation uses the configured model; every edit uses FLUX.2 [klein] 9B (MEM-109). */
    CLOUDFLARE_WORKERS_AI(null, true,
            new KnownModel("@cf/black-forest-labs/flux-2-klein-9b", "FLUX.2 klein 9B", "image/jpeg", List.of(), true, false),
            List.of(new KnownModel("@cf/black-forest-labs/flux-1-schnell", "FLUX.1 schnell", "image/jpeg", List.of(), false, false)));

    private final @Nullable String defaultEndpoint;
    private final boolean endpointRequired;
    private final @Nullable KnownModel editModel;
    private final List<KnownModel> knownModels;

    ImageProvider(@Nullable String defaultEndpoint, boolean endpointRequired, @Nullable KnownModel editModel,
                  List<KnownModel> knownModels) {
        if (knownModels.isEmpty() || knownModels.stream().map(KnownModel::modelName).distinct().count() != knownModels.size())
            throw new IllegalArgumentException("Invalid image model catalog");
        this.defaultEndpoint = defaultEndpoint;
        this.endpointRequired = endpointRequired;
        this.editModel = editModel;
        this.knownModels = List.copyOf(knownModels);
    }

    public boolean requiresKey() { return true; }

    /** Used when a connection leaves its endpoint empty; null when the manager must supply one. */
    public @Nullable String defaultEndpoint() { return defaultEndpoint; }

    public boolean endpointRequired() { return endpointRequired; }

    /** The model every edit on this protocol uses; null when edits use the connection's configured model. */
    public @Nullable KnownModel editModel() { return editModel; }

    /** Generation models the adapter serves end to end, newest first. */
    public List<KnownModel> knownModels() { return knownModels; }

    /** Resolves a tool shape through the catalog; a model outside the catalog ignores the shape. */
    public @Nullable String sizeFor(String model, @Nullable String shape) {
        var known = knownModels.stream().filter(entry -> entry.modelName().equals(model)).findFirst().orElse(null);
        return known == null ? null : known.sizeFor(shape);
    }

    /** Published metadata only: runtime behaviour follows the connection's stored model, never this entry. */
    public record KnownModel(String modelName, String displayName, String outputMediaType, List<String> sizes,
                             boolean edit, boolean deprecated) {
        static final List<String> GPT_IMAGE_SIZES = List.of("1024x1024", "1536x1024", "1024x1536");
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
