package io.memoryos.chat.image;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Implemented image protocols and the models their adapters serve end to end; native model tools are not
 * image-provider connections. The catalog prefills administration; a connection may still name another model.
 * Google and Cloudflare adapters choose the wire format per model name (see ImageProviderClient).
 */
public enum ImageProvider {
    OPENAI_IMAGE("https://api.openai.com/v1", false, null, List.of(
            new KnownModel("gpt-image-2", "GPT Image 2", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false),
            new KnownModel("gpt-image-1.5", "GPT Image 1.5", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false),
            new KnownModel("gpt-image-1", "GPT Image 1", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false))),
    /** The OpenAI image protocol on the Azure OpenAI v1 API; the model is the deployment name. */
    AZURE_OPENAI_IMAGE(null, true, null, List.of(
            new KnownModel("gpt-image-1.5", "GPT Image 1.5", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false),
            new KnownModel("gpt-image-1", "GPT Image 1", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false),
            new KnownModel("gpt-image-1-mini", "GPT Image 1 mini", "image/png", KnownModel.GPT_IMAGE_SIZES, true, false))),
    /** Gemini image models generate and edit through generateContent; Imagen models only generate through predict. */
    GOOGLE_GEMINI_IMAGE("https://generativelanguage.googleapis.com/v1beta", false,
            new KnownModel("gemini-2.5-flash-image", "Gemini 2.5 Flash Image", "image/png", List.of(), true, false),
            List.of(
                    new KnownModel("gemini-3-pro-image-preview", "Gemini 3 Pro Image", "image/png", List.of(), true, false),
                    new KnownModel("gemini-2.5-flash-image", "Gemini 2.5 Flash Image", "image/png", List.of(), true, false),
                    new KnownModel("imagen-4.0-ultra-generate-001", "Imagen 4 Ultra", "image/png", List.of(), false, false),
                    new KnownModel("imagen-4.0-generate-001", "Imagen 4", "image/png", List.of(), false, false),
                    new KnownModel("imagen-4.0-fast-generate-001", "Imagen 4 Fast", "image/png", List.of(), false, false))),
    /** FLUX.2 models edit themselves; every other model's edits use FLUX.2 [klein] 9B (MEM-109). */
    CLOUDFLARE_WORKERS_AI(null, true,
            new KnownModel("@cf/black-forest-labs/flux-2-klein-9b", "FLUX.2 klein 9B", "image/jpeg", KnownModel.FLUX_SIZES, true, false),
            List.of(
                    new KnownModel("@cf/black-forest-labs/flux-2-klein-9b", "FLUX.2 klein 9B", "image/jpeg", KnownModel.FLUX_SIZES, true, false),
                    new KnownModel("@cf/black-forest-labs/flux-2-klein-4b", "FLUX.2 klein 4B", "image/jpeg", KnownModel.FLUX_SIZES, true, false),
                    new KnownModel("@cf/black-forest-labs/flux-2-dev", "FLUX.2 dev", "image/jpeg", KnownModel.FLUX_SIZES, true, false),
                    new KnownModel("@cf/leonardo/lucid-origin", "Leonardo Lucid Origin", "image/jpeg", KnownModel.FLUX_SIZES, false, false),
                    new KnownModel("@cf/leonardo/phoenix-1.0", "Leonardo Phoenix 1.0", "image/jpeg", KnownModel.FLUX_SIZES, false, false),
                    new KnownModel("@cf/bytedance/stable-diffusion-xl-lightning", "SDXL Lightning", "image/png", KnownModel.FLUX_SIZES, false, false),
                    new KnownModel("@cf/black-forest-labs/flux-1-schnell", "FLUX.1 schnell", "image/jpeg", List.of(), false, false))),
    /** Any gateway serving the OpenAI image paths with inline base64 output; the manager types the model. */
    OPENAI_COMPATIBLE_IMAGE(null, true, null, List.of());

    private static final String CLOUDFLARE_ACCOUNT_BASE = "https://api.cloudflare.com/client/v4/accounts/";
    private static final Pattern CLOUDFLARE_ACCOUNT_ID = Pattern.compile("[0-9a-f]{32}", Pattern.CASE_INSENSITIVE);
    private static final Pattern AZURE_RESOURCE = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}", Pattern.CASE_INSENSITIVE);
    private static final Pattern AZURE_RESOURCE_URL =
            Pattern.compile("https://[a-z0-9-]+\\.(openai|cognitiveservices)\\.azure\\.com/*", Pattern.CASE_INSENSITIVE);

    private final @Nullable String defaultEndpoint;
    private final boolean endpointRequired;
    private final @Nullable KnownModel editModel;
    private final List<KnownModel> knownModels;

    ImageProvider(@Nullable String defaultEndpoint, boolean endpointRequired, @Nullable KnownModel editModel,
                  List<KnownModel> knownModels) {
        if (knownModels.stream().map(KnownModel::modelName).distinct().count() != knownModels.size()
                || (editModel != null && !editModel.edit()))
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

    /**
     * A bare Cloudflare account ID expands to its account endpoint, and a bare Azure resource name or resource
     * URL to its v1 API base; every other value is stored as given.
     */
    public String normalizeEndpoint(String endpoint) {
        if (this == CLOUDFLARE_WORKERS_AI && CLOUDFLARE_ACCOUNT_ID.matcher(endpoint).matches())
            return CLOUDFLARE_ACCOUNT_BASE + endpoint.toLowerCase(Locale.ROOT);
        if (this == AZURE_OPENAI_IMAGE && AZURE_RESOURCE.matcher(endpoint).matches())
            return "https://" + endpoint.toLowerCase(Locale.ROOT) + ".openai.azure.com/openai/v1";
        if (this == AZURE_OPENAI_IMAGE && AZURE_RESOURCE_URL.matcher(endpoint).matches())
            return endpoint.replaceAll("/+$", "") + "/openai/v1";
        return endpoint;
    }

    /** Model names are placed in request paths, so only path-safe names without traversal are accepted. */
    public static boolean validModelName(@Nullable String model) {
        return model != null && KnownModel.MODEL_NAME.matcher(model).matches() && !model.contains("..");
    }

    /** The fallback edit model for generation models that cannot edit; null when edits use the configured model. */
    public @Nullable KnownModel editModel() { return editModel; }

    /**
     * The model {@code edit_image} calls: a known edit-capable model edits itself, otherwise the provider
     * fallback applies, and a provider without a fallback edits with the configured model.
     */
    public String editModelFor(String model) {
        var known = known(model);
        if (known != null && known.edit()) return model;
        return editModel != null ? editModel.modelName() : model;
    }

    /** Generation models the adapter serves end to end, newest first. */
    public List<KnownModel> knownModels() { return knownModels; }

    /** Resolves a tool shape through the catalog; a model outside the catalog ignores the shape. */
    public @Nullable String sizeFor(String model, @Nullable String shape) {
        var known = known(model);
        return known == null ? null : known.sizeFor(shape);
    }

    private @Nullable KnownModel known(String model) {
        return knownModels.stream().filter(entry -> entry.modelName().equals(model)).findFirst().orElse(null);
    }

    /** Published metadata only: runtime behaviour follows the connection's stored model, never this entry. */
    public record KnownModel(String modelName, String displayName, String outputMediaType, List<String> sizes,
                             boolean edit, boolean deprecated) {
        static final List<String> GPT_IMAGE_SIZES = List.of("1024x1024", "1536x1024", "1024x1536");
        /** Workers AI FLUX, Leonardo and SDXL sizes: 16-pixel multiples inside every model's documented bounds. */
        static final List<String> FLUX_SIZES = List.of("1024x1024", "1280x768", "768x1280");
        // Declared here, not on the enum: enum constants are built before the enum's own static fields.
        private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9@._:/+-]{1,200}");
        private static final Pattern SIZE = Pattern.compile("[1-9][0-9]{1,4}x[1-9][0-9]{1,4}");
        private static final Set<String> MEDIA_TYPES = Set.of("image/png", "image/jpeg", "image/webp");

        public KnownModel {
            if (!validModelName(modelName)
                    || displayName == null || displayName.isBlank()
                    || outputMediaType == null || !MEDIA_TYPES.contains(outputMediaType)
                    || sizes == null || !sizes.stream().allMatch(size -> size != null && SIZE.matcher(size).matches()))
                throw new IllegalArgumentException("Invalid known image model metadata");
            sizes = List.copyOf(sizes);
        }

        /** The declared size matching a tool shape; null for an unknown shape or an empty size list. */
        public @Nullable String sizeFor(@Nullable String shape) {
            Integer aspect = aspect(shape);
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

    /** Google models take an aspect ratio instead of a pixel size; null leaves the provider default. */
    public static @Nullable String aspectRatioFor(@Nullable String shape) {
        Integer aspect = aspect(shape);
        if (aspect == null) return null;
        return aspect == 0 ? "1:1" : aspect > 0 ? "16:9" : "9:16";
    }

    /** 0 square, 1 landscape, -1 portrait; null for an unknown shape. */
    private static @Nullable Integer aspect(@Nullable String shape) {
        if (shape == null) return null;
        return switch (shape.toLowerCase(Locale.ROOT)) {
            case "square" -> 0;
            case "landscape" -> 1;
            case "portrait" -> -1;
            default -> null;
        };
    }
}
