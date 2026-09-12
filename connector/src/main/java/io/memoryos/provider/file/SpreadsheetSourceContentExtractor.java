package io.memoryos.provider.file;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.StructuredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class SpreadsheetSourceContentExtractor {
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;
    private final ObjectMapper mapper;
    public SpreadsheetSourceContentExtractor(ObjectMapper mapper) { this.mapper = mapper; }

    /** Same canonical workbook contract, with disk-backed ZIP parts for large Chat attachments. */
    public DocumentContent extractFile(Path file, String filename, String mediaType) throws ExtractionException {
        var output = new StructuredContent(mapper, SourceInputDescriptor.binary());
        try {
            if (Files.size(file) < 1 || Files.size(file) > 262_144_000) limit();
            if ("text/csv".equals(mediaType) || "text/tab-separated-values".equals(mediaType)) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    return delimited(reader, filename, output, mediaType);
                }
            }
            if (!XLSX.equals(mediaType) && !"application/vnd.ms-excel.sheet.macroEnabled.12".equals(mediaType))
                throw StructuredContent.failure(ExtractionFailure.UNSUPPORTED);
            try (var zip = ZipFile.builder().setPath(file).get()) { zipAdmission(zip, output, 512L * 1024 * 1024); }
            try (var archive = OPCPackage.open(file.toFile(), PackageAccess.READ)) {
                return workbook(archive, filename, output, mediaType);
            }
        } catch (ExtractionException exception) { throw exception; }
        catch (Exception exception) { throw StructuredContent.failure(ExtractionFailure.MALFORMED); }
    }

    public DocumentContent extract(byte[] bytes, String filename, String mediaType,
                                   SourceInputDescriptor input) throws ExtractionException {
        if (bytes.length < 1 || bytes.length > 10_485_760) throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);
        StructuredContent output = new StructuredContent(mapper, input);
        if (XLSX.equals(mediaType)) return xlsx(bytes, filename, output);
        if ("text/csv".equals(mediaType)) return csv(bytes, filename, output);
        throw StructuredContent.failure(ExtractionFailure.UNSUPPORTED);
    }

    public DocumentContent extract(InputStream stream, long size, String filename, String mediaType,
                                   SourceInputDescriptor input) throws ExtractionException {
        return extract(StructuredContent.read(stream, size, 10_485_760), filename, mediaType, input);
    }

    private DocumentContent csv(byte[] bytes, String filename, StructuredContent output) throws ExtractionException {
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes, offset, bytes.length - offset), decoder)) {
            return delimited(reader, filename, output, "text/csv");
        } catch (IOException exception) { throw StructuredContent.failure(ExtractionFailure.MALFORMED); }
    }

    private DocumentContent delimited(java.io.Reader input, String filename, StructuredContent output, String mediaType) throws ExtractionException {
        ObjectNode table = output.block("TABLE").putObject("table");
        table.put("coordinateBase", 0);
        table.put("missingCellValue", "EMPTY");
        ArrayNode cells = table.putArray("cells");
        int rows = 0;
        int columns = 0;
        try (var reader = new RecordBoundedReader(input, output);
             var parser = ("text/tab-separated-values".equals(mediaType) ? CSVFormat.TDF : CSVFormat.RFC4180).parse(reader)) {
            for (CSVRecord record : parser) {
                reader.nextRecord();
                output.checkTime();
                columns = Math.max(columns, record.size());
                if (columns > 16384) limit();
                if ((long) (rows + 1) * columns > StructuredContent.MAX_CELLS) limit();
                for (int column = 0; column < record.size(); column++) {
                    output.cell();
                    String value = record.get(column);
                    ObjectNode cell = cells.addObject();
                    cell.put("row", rows);
                    cell.put("column", column);
                    cell.put("type", "TEXT");
                    cell.put("value", value);
                    cell.put("text", value);
                    if (column > 0) output.append("\t");
                    output.append(value);
                }
                output.append("\n");
                rows++;
            }
        } catch (IOException | RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause())
                if (cause instanceof ExtractionException extraction) throw extraction;
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
        table.put("rowCount", rows);
        table.put("columnCount", columns);
        table.putArray("merges");
        return output.finish(mediaType, filename, "commons-csv");
    }

    private DocumentContent xlsx(byte[] bytes, String filename, StructuredContent output) throws ExtractionException {
        zipAdmission(bytes, output);
        try (var archive = OPCPackage.open(new ByteArrayInputStream(bytes))) {
            return workbook(archive, filename, output, XLSX);
        } catch (Exception exception) {
            if (exception instanceof ExtractionException extraction) throw extraction;
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
    }

    private DocumentContent workbook(OPCPackage archive, String filename, StructuredContent output, String mediaType) throws ExtractionException {
        try (var workbook = new XSSFWorkbook(archive)) {
            if (workbook.getNumberOfSheets() < 1 || workbook.getNumberOfSheets() > StructuredContent.MAX_TABS) limit();
            output.canonical().put("dateSystem", workbook.isDate1904() ? "1904" : "1900");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            long represented = 0;
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                output.checkTime();
                var sheet = workbook.getSheetAt(index);
                int rows = sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
                int columns = 0;
                for (var row : sheet) {
                    output.checkTime();
                    columns = Math.max(columns, row.getLastCellNum());
                }
                for (var merge : sheet.getMergedRegions()) {
                    rows = Math.max(rows, merge.getLastRow() + 1);
                    columns = Math.max(columns, merge.getLastColumn() + 1);
                }
                if ((represented += (long) rows * columns) > StructuredContent.MAX_CELLS) limit();
                ObjectNode block = output.block("TABLE");
                block.put("text", sheet.getSheetName());
                ObjectNode provenance = block.putObject("provenance");
                provenance.put("sheetIndex", index);
                provenance.put("sheetName", sheet.getSheetName());
                provenance.put("visibility", workbook.getSheetVisibility(index).name());
                ObjectNode table = block.putObject("table");
                table.put("rowCount", rows);
                table.put("columnCount", columns);
                table.put("coordinateBase", 0);
                table.put("missingCellValue", "EMPTY");
                ArrayNode merges = table.putArray("merges");
                for (var merge : sheet.getMergedRegions()) {
                    ObjectNode range = merges.addObject();
                    range.put("startRowIndex", merge.getFirstRow());
                    range.put("endRowIndex", merge.getLastRow() + 1);
                    range.put("startColumnIndex", merge.getFirstColumn());
                    range.put("endColumnIndex", merge.getLastColumn() + 1);
                    range.put("range", merge.formatAsString());
                }
                ArrayNode cells = table.putArray("cells");
                output.append(sheet.getSheetName() + "\n");
                for (var row : sheet) for (var value : row) {
                    output.cell();
                    XSSFCell source = (XSSFCell) value;
                    ObjectNode cell = cells.addObject();
                    cell.put("row", source.getRowIndex());
                    cell.put("column", source.getColumnIndex());
                    String address = new CellAddress(source).formatAsString();
                    cell.put("address", address);
                    if (source.getCellType() == CellType.FORMULA) cell.put("formula", source.getCellFormula());
                    CellType type = source.getCellType() == CellType.FORMULA ? source.getCachedFormulaResultType() : source.getCellType();
                    cell.put("type", type.name());
                    String text = value(source, type, cell, formatter, workbook.isDate1904());
                    cell.put("text", text);
                    cell.put("numberFormat", source.getCellStyle().getDataFormatString());
                    if (source.getHyperlink() != null) cell.put("hyperlink", source.getHyperlink().getAddress());
                    if (source.getCellComment() != null) cell.put("note", source.getCellComment().getString().getString());
                    if (!text.isEmpty()) output.append(address + ": " + text + "\n");
                }
            }
            return output.finish(mediaType, filename, "apache-poi-xlsx");
        } catch (EncryptedDocumentException exception) {
            throw StructuredContent.failure(ExtractionFailure.ENCRYPTED);
        } catch (IOException | RuntimeException exception) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
    }

    private static String value(XSSFCell cell, CellType type, ObjectNode output,
                                DataFormatter formatter, boolean date1904) {
        // A cached value is data, not an instruction. No FormulaEvaluator or external-link resolver exists here.
        if (cell.getCellType() == CellType.FORMULA && cell.getRawValue() == null) {
            output.put("cachedValuePresent", false);
            return cell.getCellFormula();
        }
        return switch (type) {
            case STRING -> {
                String value = cell.getStringCellValue();
                output.put("value", value);
                yield value;
            }
            case NUMERIC -> {
                output.put("rawValue", cell.getRawValue());
                output.put("value", cell.getNumericCellValue());
                yield formatter.formatRawCellContents(cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(),
                        cell.getCellStyle().getDataFormatString(), date1904);
            }
            case BOOLEAN -> {
                output.put("value", cell.getBooleanCellValue());
                yield Boolean.toString(cell.getBooleanCellValue());
            }
            case ERROR -> {
                String error = FormulaError.forInt(cell.getErrorCellValue()).getString();
                output.put("value", error);
                yield error;
            }
            case BLANK, _NONE -> "";
            case FORMULA -> throw new IllegalArgumentException("invalid cached formula type");
        };
    }

    private static void zipAdmission(byte[] bytes, StructuredContent output) throws ExtractionException {
        try (var channel = new SeekableInMemoryByteChannel(bytes);
             var zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            zipAdmission(zip, output, MAX_EXPANDED_BYTES);
        } catch (IOException exception) { throw StructuredContent.failure(ExtractionFailure.MALFORMED); }
    }

    private static void zipAdmission(ZipFile zip, StructuredContent output, long maxExpandedBytes) throws ExtractionException {
        int entries = 0;
        Set<String> names = new HashSet<>();
        boolean contentTypes = false;
        boolean workbook = false;
        XmlAdmission admission = new XmlAdmission(output, maxExpandedBytes);
        try {
            var factory = javax.xml.parsers.SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();
            parser.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            byte[] buffer = new byte[16_384];
            CRC32 checksum = new CRC32();
            var members = zip.getEntries();
            while (members.hasMoreElements()) {
                var entry = members.nextElement();
                output.checkTime();
                if (++entries > 10_000 || !names.add(entry.getName())) limit();
                contentTypes |= "[Content_Types].xml".equals(entry.getName());
                workbook |= "xl/workbook.xml".equals(entry.getName());
                admission.expanded = 0;
                checksum.reset();
                try (InputStream member = zip.getInputStream(entry)) {
                    InputStream bounded = admission.wrap(new CheckedInputStream(member, checksum));
                    String name = entry.getName();
                    if (name.endsWith(".xml") || name.endsWith(".rels")) {
                        parser.parse(bounded, admission);
                    } else {
                        while (bounded.read(buffer) != -1) { output.checkTime(); }
                    }
                }
                if (admission.expanded != entry.getSize() || checksum.getValue() != entry.getCrc())
                    throw StructuredContent.failure(ExtractionFailure.MALFORMED);
                if (admission.expanded > 1_048_576 && (entry.getCompressedSize() < 1
                        || admission.expanded > entry.getCompressedSize() * 100)) limit();
            }
            if (!contentTypes || !workbook) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        } catch (IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ExtractionException extraction) throw extraction;
            }
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
    }

    private static final class XmlAdmission extends org.xml.sax.helpers.DefaultHandler {
        private final StructuredContent output;
        private final long maxExpandedBytes;
        private long total;
        private long expanded;
        private long text;
        private int nodes;
        private int cells;
        private int strings;
        private int rows;
        private int depth;

        XmlAdmission(StructuredContent output, long maxExpandedBytes) { this.output = output; this.maxExpandedBytes = maxExpandedBytes; }

        InputStream wrap(InputStream input) {
            return new java.io.FilterInputStream(input) {
                @Override public int read() throws IOException {
                    int value = in.read();
                    count(value < 0 ? 0 : 1);
                    return value;
                }
                @Override public int read(byte @org.jspecify.annotations.NonNull [] buffer, int offset, int length) throws IOException {
                    int value = in.read(buffer, offset, length);
                    count(Math.max(0, value));
                    return value;
                }
                @Override public void close() { /* The ZIP owner advances and closes the stream. */ }
            };
        }

        private void count(int bytes) throws IOException {
            expanded += bytes;
            total += bytes;
            try {
                output.checkTime();
                if (total > maxExpandedBytes) limit();
            } catch (ExtractionException exception) { throw new IOException(exception); }
        }

        @Override public void startElement(String uri, String local, String name, org.xml.sax.Attributes attributes)
                throws org.xml.sax.SAXException {
            if (++nodes > 2_000_000 || ++depth > 100
                    || "c".equals(local) && ++cells > StructuredContent.MAX_CELLS
                    || "si".equals(local) && ++strings > StructuredContent.MAX_CELLS
                    || "row".equals(local) && ++rows > StructuredContent.MAX_CELLS) {
                throw new org.xml.sax.SAXException(StructuredContent.failure(ExtractionFailure.WRITE_LIMIT));
            }
        }

        @Override public void endElement(String uri, String local, String name) { depth--; }

        @Override public void characters(char[] characters, int start, int length) throws org.xml.sax.SAXException {
            if ((text += length) > 8_000_000) {
                throw new org.xml.sax.SAXException(StructuredContent.failure(ExtractionFailure.WRITE_LIMIT));
            }
        }

        @Override public void error(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException { throw exception; }
    }

    /** Stops pathological records before Commons CSV allocates an unbounded field or column list. */
    private static final class RecordBoundedReader extends java.io.FilterReader {
        private final StructuredContent output;
        private int consumed;
        RecordBoundedReader(java.io.Reader reader, StructuredContent output) throws IOException {
            super(new java.io.PushbackReader(reader, 1)); this.output = output;
            int first = in.read();
            if (first >= 0 && first != '\uFEFF') ((java.io.PushbackReader) in).unread(first);
        }
        void nextRecord() { consumed = 0; }
        private void check(int count) throws IOException {
            try {
                output.checkTime();
                consumed += Math.max(0, count);
                if (consumed > 2 * StructuredContent.MAX_TEXT + 65536) limit();
            } catch (ExtractionException failure) { throw new IOException(failure); }
        }
        @Override public int read() throws IOException { int value = super.read(); check(value < 0 ? 0 : 1); return value; }
        @Override public int read(char @org.jspecify.annotations.NonNull [] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, Math.min(length, 8192)); check(read); return read;
        }
    }

    private static void limit() throws ExtractionException { throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT); }
}
