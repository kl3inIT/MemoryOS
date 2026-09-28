package io.memoryos.library;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * The small rendering the file library shows in place of a full image, an upload's or a generated one's. The
 * untrusted-image decoding and the area-averaged scaling live here too, and Chat's image edits reuse them, so an
 * edit and a thumbnail read an image under the same pixel ceiling and subsampling and scale it alike.
 */
public final class ImageThumbnails {
    /** Long side of a thumbnail: twice the largest cell the library draws, so it stays sharp on a dense display. */
    public static final int MAX_SIDE = 512;
    public static final String MEDIA_TYPE = "image/jpeg";
    /**
     * Below this an artifact is already cheap to send, and a second stored object would cost more in storage
     * rows and sweep work than the transfer it saves.
     */
    public static final int MIN_SOURCE_BYTES = 64 * 1024;
    private static final float QUALITY = 0.8f;
    /** An image of more pixels than this is refused before it is decoded. */
    private static final long MAX_PIXELS = 144_000_000L;
    /**
     * Decoding subsamples to about four times this side: the working size of an edit (Chat's
     * {@code ImageEditImages.MAX_SIDE}), which is larger than a thumbnail and so serves both.
     */
    private static final int DECODE_SIDE = 1024;

    /** A thumbnail's bytes and the type they were encoded as. */
    public record Rendered(byte[] bytes, String mediaType) {}

    private ImageThumbnails() {}

    /**
     * Renders a thumbnail of an artifact, or empty when rendering would not pay: an original already small
     * enough to send whole, bytes this build cannot decode (a provider's WebP, or an object that no longer
     * holds an image), and a rendering no smaller than what it was made from. The caller then serves the
     * original, which stays correct, only larger.
     */
    public static Optional<Rendered> render(byte[] bytes) {
        if (bytes.length <= MIN_SOURCE_BYTES) return Optional.empty();
        try {
            var source = decode(bytes);
            double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            byte[] rendered = jpeg(scale(source, width, height));
            return rendered.length < bytes.length ? Optional.of(new Rendered(rendered, MEDIA_TYPE)) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** Decodes an untrusted PNG or JPEG under the pixel ceiling, subsampling a very large one while reading. */
    public static BufferedImage decode(byte[] bytes) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg")) throw new IOException("Unsupported image");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) throw new IOException("Image too large");
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.ceilDiv(Math.max(width, height), 4 * DECODE_SIDE));
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                var decoded = reader.read(0, parameters);
                if (decoded == null) throw new IOException("Unreadable image");
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException malformed) {
            throw new IOException("Unreadable image");
        }
    }

    /** Area-averaged reduction to an exact size; image edits reuse it so both paths scale alike. */
    public static BufferedImage scale(BufferedImage source, int width, int height) {
        var current = rgb(source);
        int w = current.getWidth();
        int h = current.getHeight();
        while (w / 2 >= width && h / 2 >= height) { // Halving steps keep a large reduction from aliasing.
            w /= 2;
            h /= 2;
            current = draw(current, w, h);
        }
        return w == width && h == height ? current : draw(current, width, height);
    }

    private static BufferedImage draw(BufferedImage image, int width, int height) {
        var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            smooth(graphics);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** Opaque RGB; transparency is flattened onto white, as a viewer shows it. */
    private static BufferedImage rgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) return image;
        var target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** Bilinear, quality-first drawing; edits draw their masks with it too. */
    public static void smooth(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG writer");
        var writer = writers.next();
        var out = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
