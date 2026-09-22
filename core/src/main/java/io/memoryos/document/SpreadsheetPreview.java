package io.memoryos.document;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.jspecify.annotations.Nullable;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Onyx {@code parse_spreadsheet_for_preview} ({@code chat_utils.py}): each sheet of an xlsx as CSV text, cut at a row
 * boundary past {@link #MAX_CHARS_PER_SHEET}. Reads with POI's streaming reader, shows cached formula values and never
 * evaluates formulas.
 *
 * <p>A workbook is never sent to the browser as bytes, because the app ships no client-side workbook parser.
 * Both an owner-private Chat file and a Document original are read through this, so it belongs to no one
 * capability.
 */
public final class SpreadsheetPreview {
    /** Onyx {@code MAX_PREVIEW_CHARS_PER_SHEET}. */
    public static final int MAX_CHARS_PER_SHEET = 500_000;

    public record Sheet(String name, String csv, boolean truncated) {}

    private SpreadsheetPreview() {}

    public static List<Sheet> parse(InputStream xlsx) throws IOException {
        return parse(xlsx, MAX_CHARS_PER_SHEET);
    }

    static List<Sheet> parse(InputStream xlsx, int maxChars) throws IOException {
        // As Onyx, read from a temporary file: opening a file inflates each part on demand, where opening a stream
        // would inflate the whole package into the heap first.
        var file = java.nio.file.Files.createTempFile("memoryos-xlsx-preview", ".xlsx");
        try {
            try (var out = java.nio.file.Files.newOutputStream(file)) { xlsx.transferTo(out); }
            return parse(file, maxChars);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static List<Sheet> parse(java.nio.file.Path file, int maxChars) throws IOException {
        OPCPackage workbook = null;
        try {
            workbook = OPCPackage.open(file.toFile(), org.apache.poi.openxml4j.opc.PackageAccess.READ);
            var reader = new XSSFReader(workbook);
            var strings = new ReadOnlySharedStringsTable(workbook);
            var styles = reader.getStylesTable();
            var formatter = new DataFormatter();
            var sheets = new ArrayList<Sheet>();
            var iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (iterator.hasNext()) {
                try (var sheet = iterator.next()) {
                    var csv = new CsvSheet(maxChars);
                    var parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, csv, formatter, false));
                    try {
                        parser.parse(new InputSource(sheet));
                    } catch (Full ignored) {
                        // The sheet reached its preview budget; later rows are not read.
                    }
                    sheets.add(new Sheet(iterator.getSheetName(), csv.text.toString(), csv.truncated));
                }
            }
            return List.copyOf(sheets);
        } catch (OpenXML4JException | SAXException | ParserConfigurationException | org.apache.poi.ooxml.POIXMLException
                | org.apache.poi.UnsupportedFileFormatException | org.apache.poi.util.RecordFormatException failed) {
            throw new IOException("Not a readable xlsx workbook", failed);
        } finally {
            // close() would try to save a read-only package; revert releases it.
            if (workbook != null) workbook.revert();
        }
    }

    private static final class Full extends RuntimeException {
        Full() { super(null, null, false, false); }
    }

    private static final class CsvSheet implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final int maxChars;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder row = new StringBuilder();
        private boolean truncated;
        private int column;

        CsvSheet(int maxChars) { this.maxChars = maxChars; }

        @Override public void startRow(int rowNum) {
            row.setLength(0);
            column = 0;
        }

        @Override public void endRow(int rowNum) {
            row.append('\n');
            if (text.length() + row.length() > maxChars) {
                truncated = true;
                throw new Full();
            }
            text.append(row);
        }

        @Override public void cell(@Nullable String reference, @Nullable String value, @Nullable XSSFComment comment) {
            int index = reference == null ? column : Math.max(column, new CellReference(reference).getCol());
            // Missing cells before this one are empty columns.
            row.append(",".repeat(index - column + (column > 0 ? 1 : 0)));
            row.append(quote(value == null ? "" : value));
            column = index + 1;
        }

        private static String quote(String value) {
            if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0)
                return value;
            return '"' + value.replace("\"", "\"\"") + '"';
        }
    }
}
