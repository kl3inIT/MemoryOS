package io.memoryos.chat.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * The small rendering the file library shows in place of a full generated image. Decoding and scaling are the
 * ones edits already use, so an untrusted artifact is read under the same pixel ceiling and subsampling.
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
            var source = ImageEditImages.decode(bytes);
            double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            byte[] rendered = jpeg(ImageEditImages.scale(source, width, height));
            return rendered.length < bytes.length ? Optional.of(new Rendered(rendered, MEDIA_TYPE)) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static byte[] jpeg(java.awt.image.BufferedImage image) throws IOException {
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
