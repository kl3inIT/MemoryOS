package io.memoryos.chat.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageThumbnailsTest {
    /** Noise, so the PNG carries the weight a generated image does and a smaller rendering is a real saving. */
    private static byte[] noise(int width, int height, String format) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var random = new Random(20260921);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt(0xFFFFFF));
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    @Test void aLargeImageIsRenderedWithinTheThumbnailSideAndFarSmaller() throws Exception {
        byte[] original = noise(1024, 768, "png");
        var rendered = ImageThumbnails.render(original).orElseThrow();

        assertEquals(ImageThumbnails.MEDIA_TYPE, rendered.mediaType());
        var decoded = ImageIO.read(new ByteArrayInputStream(rendered.bytes()));
        assertEquals(ImageThumbnails.MAX_SIDE, decoded.getWidth());
        assertEquals(384, decoded.getHeight()); // The aspect ratio is kept.
        assertTrue(rendered.bytes().length < original.length / 4,
                "thumbnail " + rendered.bytes().length + " vs original " + original.length);
    }

    @Test void anImageAlreadyCheapToSendIsLeftAlone() throws Exception {
        // Under the floor a second stored object costs more than the transfer it would save.
        byte[] small = noise(8, 8, "jpg");
        assertTrue(small.length <= ImageThumbnails.MIN_SOURCE_BYTES);
        assertTrue(ImageThumbnails.render(small).isEmpty());
    }

    @Test void bytesThisBuildCannotDecodeYieldNoThumbnail() throws Exception {
        byte[] text = new byte[ImageThumbnails.MIN_SOURCE_BYTES + 1];
        System.arraycopy("not an image".getBytes(StandardCharsets.UTF_8), 0, text, 0, 12);
        assertTrue(ImageThumbnails.render(text).isEmpty());
        // A GIF has no reader on this path, as for a provider's WebP; the caller serves the artifact itself.
        assertTrue(ImageThumbnails.render(noise(512, 512, "gif")).isEmpty());
    }
}
