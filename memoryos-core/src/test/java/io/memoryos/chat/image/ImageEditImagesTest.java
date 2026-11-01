package io.memoryos.chat.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageEditImagesTest {
    private static byte[] encode(BufferedImage image, String format) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage solid(int width, int height, int type, int argb) {
        var image = new BufferedImage(width, height, type);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
        return image;
    }

    @Test void largeImagesFitTheLongSideOnMultiplesOf16() throws Exception {
        var working = ImageEditImages.prepare(encode(solid(2000, 1000, BufferedImage.TYPE_INT_RGB, Color.RED.getRGB()), "png"));
        assertEquals(1024, working.width());
        assertEquals(512, working.height());
        var decoded = ImageIO.read(new ByteArrayInputStream(working.png()));
        assertEquals(1024, decoded.getWidth());
        assertEquals(512, decoded.getHeight());
    }

    @Test void smallImagesKeepTheirSizeRoundedToMultiplesOf16() throws Exception {
        var working = ImageEditImages.prepare(encode(solid(100, 80, BufferedImage.TYPE_INT_RGB, Color.RED.getRGB()), "jpg"));
        assertEquals(96, working.width());
        assertEquals(80, working.height());
    }

    @Test void onlyPngAndJpegAreAccepted() throws Exception {
        assertThrows(IOException.class, () -> ImageEditImages.prepare("not an image".getBytes(StandardCharsets.UTF_8)));
        byte[] gif = encode(solid(16, 16, BufferedImage.TYPE_INT_RGB, Color.RED.getRGB()), "gif");
        assertThrows(IOException.class, () -> ImageEditImages.prepare(gif));
    }

    @Test void maskCoverageCountsOpaqueWhiteAndKeepsTransparentPixels() throws Exception {
        assertEquals(1.0, ImageEditImages.mask(encode(solid(16, 16, BufferedImage.TYPE_INT_RGB, Color.WHITE.getRGB()), "png"), 16, 16).coverage());
        assertEquals(0.0, ImageEditImages.mask(encode(solid(16, 16, BufferedImage.TYPE_INT_RGB, Color.BLACK.getRGB()), "png"), 16, 16).coverage());
        assertEquals(0.0, ImageEditImages.mask(encode(solid(16, 16, BufferedImage.TYPE_INT_ARGB, 0x00FFFFFF), "png"), 16, 16).coverage());
    }

    @Test void compositeTakesTheEditOnlyInsideTheSelection() throws Exception {
        var original = ImageEditImages.prepare(encode(solid(64, 64, BufferedImage.TYPE_INT_RGB, Color.RED.getRGB()), "png"));
        var half = solid(64, 64, BufferedImage.TYPE_INT_RGB, Color.BLACK.getRGB());
        for (int y = 0; y < 64; y++) for (int x = 0; x < 32; x++) half.setRGB(x, y, Color.WHITE.getRGB());
        var mask = ImageEditImages.mask(encode(half, "png"), 64, 64);
        assertEquals(0.5, mask.coverage());
        // The provider may answer at another size; the edit is scaled back to the working image.
        byte[] edited = encode(solid(128, 128, BufferedImage.TYPE_INT_RGB, Color.BLUE.getRGB()), "png");
        var composed = ImageIO.read(new ByteArrayInputStream(ImageEditImages.composite(original, edited, mask)));
        for (int y = 0; y < 64; y++) for (int x = 32; x < 64; x++) assertEquals(Color.RED.getRGB(), composed.getRGB(x, y));
        assertEquals(Color.BLUE.getRGB(), composed.getRGB(8, 32));
    }
}
