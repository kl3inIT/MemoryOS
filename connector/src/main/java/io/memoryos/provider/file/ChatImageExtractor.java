package io.memoryos.provider.file;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.StructuredContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.ObjectMapper;

final class ChatImageExtractor {
    static DocumentContent extract(Path file, String filename, String mediaType, ObjectMapper mapper) throws IOException, ExtractionException {
        if (Files.size(file) > 20971520) throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);
        int width; int height;
        try {
            try (var input = javax.imageio.ImageIO.createImageInputStream(file.toFile())) {
                var readers = javax.imageio.ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    width = reader.getWidth(0); height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long) width * height > 144000000)
                        throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);
                    if (reader.getNumImages(true) != 1) throw StructuredContent.failure(ExtractionFailure.UNSUPPORTED);
                    var parameters = reader.getDefaultReadParam();
                    int sample = Math.max(1, Math.ceilDiv(Math.max(width, height), 2048));
                    parameters.setSourceSubsampling(sample, sample, 0, 0);
                    var decoded = reader.read(0, parameters);
                    if (decoded == null) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
                    decoded.flush();
                }
                finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException malformed) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
        var output = new StructuredContent(mapper, SourceInputDescriptor.binary());
        String description = "Image attachment " + width + "x" + height + ". Visual content must be read by a vision-capable model, not inferred from this metadata.";
        output.append(description); output.block("PARAGRAPH").put("text", description);
        return output.finish(mediaType, filename, "chat-imageio");
    }
    private ChatImageExtractor() {}
}
