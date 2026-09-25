package io.memoryos.chat.image;

import io.memoryos.library.ImageThumbnails;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Working images for edits: untrusted PNG/JPEG decoding, size normalization for provider cost, and mask
 * compositing that keeps everything outside the selected area pixel for pixel.
 */
public final class ImageEditImages {
    /** Longest side of the working image; bounds provider tile cost and memory. */
    public static final int MAX_SIDE = 1024;
    /** A mask selecting at least this share is a whole-image edit and needs no compositing. */
    public static final double FULL_COVERAGE = 0.999;
    private static final int MULTIPLE = 16;
    private static final int MIN_SIDE = MULTIPLE; // Klein accepts small sizes (48 px probed); only a zero side is invalid.
    /** Inward feather of about 1.2% of the long side, so a provider's tone shift blends at the mask edge. */
    private static final float FEATHER_SHARE = 0.012f;
    private static final int MIN_FEATHER = 4;

    /** A normalized working image: the PNG sent to the provider and the same pixels kept for compositing. */
    public record Working(byte[] png, int width, int height, BufferedImage pixels) {}

    /** Per-pixel change weights in [0, 1] at the working size, and the share of pixels selected. */
    public record Mask(float[] weights, double coverage) {}

    private ImageEditImages() {}

    /** Decodes an untrusted PNG or JPEG and scales it to fit {@link #MAX_SIDE}, with sides on multiples of 16. */
    public static Working prepare(byte[] bytes) throws IOException {
        var source = ImageThumbnails.decode(bytes);
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = side(source.getWidth() * scale);
        int height = side(source.getHeight() * scale);
        var pixels = ImageThumbnails.scale(source, width, height);
        return new Working(png(pixels), width, height, pixels);
    }

    /** White (opaque) marks the area that may change; black or transparent pixels are kept. */
    public static Mask mask(byte[] bytes, int width, int height) throws IOException {
        var scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            ImageThumbnails.smooth(graphics);
            graphics.drawImage(ImageThumbnails.decode(bytes), 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        float[] weights = new float[width * height];
        int selected = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = scaled.getRGB(x, y);
                float alpha = ((argb >>> 24) & 0xFF) / 255f;
                float luminance = (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF)) / 255f;
                if (luminance * alpha >= 0.5f) {
                    weights[y * width + x] = 1f;
                    selected++;
                }
            }
        }
        // Feather inward only: the edge softens inside the selection and nothing outside it changes.
        int radius = Math.max(MIN_FEATHER, Math.round(Math.max(width, height) * FEATHER_SHARE));
        float[] soft = blur(weights, width, height, radius);
        for (int i = 0; i < weights.length; i++) weights[i] *= soft[i];
        return new Mask(weights, (double) selected / weights.length);
    }

    /** Takes the edited image only where the mask selects; every other pixel is the working original. */
    public static byte[] composite(Working original, byte[] edited, Mask mask) throws IOException {
        int width = original.width();
        int height = original.height();
        if (mask.weights().length != width * height) throw new IllegalArgumentException("Mask size does not match the image");
        var changed = ImageThumbnails.scale(ImageThumbnails.decode(edited), width, height);
        var out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float weight = mask.weights()[y * width + x];
                int keep = original.pixels().getRGB(x, y);
                out.setRGB(x, y, weight <= 0f ? keep : weight >= 1f ? changed.getRGB(x, y) : blend(keep, changed.getRGB(x, y), weight));
            }
        }
        return png(out);
    }

    private static int side(double value) {
        return Math.max(MIN_SIDE, (int) Math.round(value / MULTIPLE) * MULTIPLE);
    }

    private static int blend(int keep, int edit, float weight) {
        return (mix(keep >> 16, edit >> 16, weight) << 16) | (mix(keep >> 8, edit >> 8, weight) << 8) | mix(keep, edit, weight);
    }

    private static int mix(int keep, int edit, float weight) {
        return Math.round((keep & 0xFF) * (1f - weight) + (edit & 0xFF) * weight);
    }

    /** Separable box blur with edge clamping. */
    private static float[] blur(float[] values, int width, int height, int radius) {
        float window = 2f * radius + 1f;
        float[] across = new float[values.length];
        float[] result = new float[values.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            float sum = 0f;
            for (int i = -radius; i <= radius; i++) sum += values[row + clamp(i, width)];
            for (int x = 0; x < width; x++) {
                across[row + x] = sum / window;
                sum += values[row + clamp(x + radius + 1, width)] - values[row + clamp(x - radius, width)];
            }
        }
        for (int x = 0; x < width; x++) {
            float sum = 0f;
            for (int i = -radius; i <= radius; i++) sum += across[clamp(i, height) * width + x];
            for (int y = 0; y < height; y++) {
                result[y * width + x] = sum / window;
                sum += across[clamp(y + radius + 1, height) * width + x] - across[clamp(y - radius, height) * width + x];
            }
        }
        return result;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", stream)) throw new IOException("PNG encoder unavailable");
        }
        return out.toByteArray();
    }
}
