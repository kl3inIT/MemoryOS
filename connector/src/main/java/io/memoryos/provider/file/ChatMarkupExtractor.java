package io.memoryos.provider.file;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.StructuredContent;
import java.nio.file.Path;
import org.apache.tika.config.OutputLimits;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import tools.jackson.databind.ObjectMapper;

/** Existing Tika parsers: inert body text only, no nested attachments or external fetches. */
final class ChatMarkupExtractor {
    static DocumentContent extract(Path file, String filename, String mediaType, ObjectMapper mapper) throws ExtractionException {
        var output = new StructuredContent(mapper, SourceInputDescriptor.binary());
        Parser parser = switch (mediaType) {
            case "message/rfc822" -> new org.apache.tika.parser.mail.RFC822Parser();
            case "application/epub+zip" -> new org.apache.tika.parser.epub.EpubParser();
            // Office formats arrive here only when Docling could not read them; see
            // DoclingSourceContentExtractor.fallBack.
            case "application/pdf" -> new org.apache.tika.parser.pdf.PDFParser();
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    -> new org.apache.tika.parser.microsoft.ooxml.OOXMLParser();
            default -> new org.apache.tika.parser.html.JSoupParser();
        };
        var context = new ParseContext();
        // Text layers only: OCR is Docling's job, and a fallback that ran it would be a second OCR
        // path with its own quality nobody measured.
        var pdfConfig = new org.apache.tika.parser.pdf.PDFParserConfig();
        pdfConfig.getOcr().setStrategy(org.apache.tika.parser.pdf.OcrConfig.Strategy.NO_OCR);
        context.set(org.apache.tika.parser.pdf.PDFParserConfig.class, pdfConfig);
        context.set(OutputLimits.class, new OutputLimits(StructuredContent.MAX_TEXT, true, 100, 10, 1000000, 100));
        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
            @Override public boolean shouldParseEmbedded(Metadata metadata, ParseContext ignored) { return false; }
            @Override public void parseEmbedded(TikaInputStream stream, org.xml.sax.ContentHandler handler, Metadata metadata,
                                                ParseContext ignored, boolean html) { }
        });
        var handler = new BodyContentHandler(StructuredContent.MAX_TEXT);
        try (var input = TikaInputStream.get(file)) {
            parser.parse(input, handler, new Metadata(), context);
            String text = handler.toString();
            output.append(text);
            output.add(ExtractedDocument.Kind.PARAGRAPH, text);
            return output.finish(mediaType, filename, "chat-tika-body");
        } catch (ExtractionException limit) { throw limit; }
        catch (Exception invalid) {
            if (org.apache.tika.exception.WriteLimitReachedException.isWriteLimitReached(invalid))
                throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);
            if (invalid instanceof org.apache.tika.exception.EncryptedDocumentException)
                throw StructuredContent.failure(ExtractionFailure.ENCRYPTED);
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
    }
    private ChatMarkupExtractor() {}
}
