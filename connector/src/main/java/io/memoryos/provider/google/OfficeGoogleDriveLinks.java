package io.memoryos.provider.google;

import static io.memoryos.connector.GoogleDriveProviderException.Failure.*;
import static io.memoryos.provider.google.OfflineGoogleDriveLinkReader.failure;

import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.provider.StructuredContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Reads package XML and relation strings only; no Office objects can dereference external relations. */
final class OfficeGoogleDriveLinks {
    private final ZipFile zip;
    private final OfflineGoogleDriveLinkReader.Links links;
    private static final java.util.regex.Pattern CELL = java.util.regex.Pattern.compile("[A-Z]{1,3}[1-9][0-9]{0,6}");

    private OfficeGoogleDriveLinks(ZipFile zip, OfflineGoogleDriveLinkReader.Links links) {
        this.zip = zip;
        this.links = links;
    }

    static void read(byte[] bytes, String mediaType, OfflineGoogleDriveLinkReader.Links links) throws IOException {
        try (var channel = new SeekableInMemoryByteChannel(bytes);
             var zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            admit(zip, links);
            var reader = new OfficeGoogleDriveLinks(zip, links);
            if (mediaType.contains("spreadsheetml")) reader.spreadsheet();
            else if (mediaType.contains("wordprocessingml")) reader.word();
            else reader.presentation();
        }
    }

    private void spreadsheet() throws IOException {
        Document workbook = xml("xl/workbook.xml");
        Map<String, Relation> relations = relations("xl/workbook.xml");
        var shared = new ArrayList<String>();
        if (zip.getEntry("xl/sharedStrings.xml") != null) {
            for (Element item : elements(xml("xl/sharedStrings.xml"), "si")) shared.add(text(item, "t"));
        }
        List<Element> sheets = elements(workbook, "sheet");
        if (sheets.isEmpty()) throw failure(MALFORMED);
        if (sheets.size() > StructuredContent.MAX_TABS) throw failure(LIMIT_EXCEEDED);
        Set<String> parts = new HashSet<>();
        long represented = 0;
        for (Element sheet : sheets) {
            Relation relation = relations.get(attribute(sheet, "id"));
            String part = internal("xl/workbook.xml", relation);
            if (!parts.add(part)) throw failure(MALFORMED);
            String title = OfflineGoogleDriveLinkReader.label(sheet.getAttribute("name"));
            Document data = xml(part);
            int rows = 0;
            int columns = 0;
            Set<String> cells = new HashSet<>();
            for (Element row : elements(data, "row")) {
                int number = Integer.parseInt(row.getAttribute("r"));
                if (number < 1 || number > 1_048_576) throw failure(MALFORMED);
                rows = Math.max(rows, number);
            }
            for (Element cell : elements(data, "c")) {
                String address = cell.getAttribute("r");
                CellAddress coordinate = address(address);
                if (!cells.add(address)) throw failure(MALFORMED);
                rows = Math.max(rows, coordinate.getRow() + 1);
                columns = Math.max(columns, coordinate.getColumn() + 1);
                String location = title + "!" + address;
                if ("s".equals(cell.getAttribute("t"))) {
                    int index;
                    try { index = Integer.parseInt(text(cell, "v")); }
                    catch (NumberFormatException exception) { throw failure(MALFORMED); }
                    if (index < 0 || index >= shared.size()) throw failure(MALFORMED);
                    links.text(shared.get(index), location);
                } else {
                    links.text(text(cell, "t") + text(cell, "v"), location);
                }
                links.text(text(cell, "f"), location);
            }
            for (Element merge : elements(data, "mergeCell")) {
                CellRangeAddress range = range(merge.getAttribute("ref"));
                rows = Math.max(rows, range.getLastRow() + 1);
                columns = Math.max(columns, range.getLastColumn() + 1);
            }
            if ((represented += (long) rows * columns) > StructuredContent.MAX_CELLS) throw failure(LIMIT_EXCEEDED);
            Map<String, Relation> targets = relations(part);
            for (Element hyperlink : elements(data, "hyperlink")) {
                String reference = hyperlink.getAttribute("ref");
                range(reference);
                external(targets, attribute(hyperlink, "id"), title + "!" + reference);
            }
            for (Relation target : targets.values()) {
                if (!target.external && target.type.endsWith("/comments")) {
                    for (Element comment : elements(xml(internal(part, target)), "comment")) {
                        String reference = comment.getAttribute("ref");
                        address(reference);
                        links.text(text(comment, "t"), title + "!" + reference);
                    }
                }
            }
        }
    }

    private static CellAddress address(String reference) {
        if (!CELL.matcher(reference).matches()) throw failure(MALFORMED);
        var address = new CellAddress(reference);
        if (address.getRow() >= 1_048_576 || address.getColumn() >= 16_384) throw failure(MALFORMED);
        return address;
    }

    private static CellRangeAddress range(String reference) {
        String[] endpoints = reference.split(":", -1);
        if (endpoints.length < 1 || endpoints.length > 2) throw failure(MALFORMED);
        CellAddress first = address(endpoints[0]);
        CellAddress last = endpoints.length == 1 ? first : address(endpoints[1]);
        if (first.getRow() > last.getRow() || first.getColumn() > last.getColumn()) throw failure(MALFORMED);
        return new CellRangeAddress(first.getRow(), last.getRow(), first.getColumn(), last.getColumn());
    }

    private void word() throws IOException {
        if (zip.getEntry("word/document.xml") == null) throw failure(MALFORMED);
        var entries = zip.getEntries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.equals("word/document.xml") || name.matches("word/(header[0-9]+|footer[0-9]+|footnotes|endnotes)\\.xml")) {
                paragraphs(name, name.equals("word/document.xml") ? "Document" : name.substring(5, name.length() - 4), "hyperlink");
            }
        }
    }

    private void presentation() throws IOException {
        Document presentation = xml("ppt/presentation.xml");
        Map<String, Relation> relations = relations("ppt/presentation.xml");
        List<Element> slides = elements(presentation, "sldId");
        if (slides.size() > OfflineGoogleDriveLinkReader.MAX_PAGES) throw failure(LIMIT_EXCEEDED);
        int number = 0;
        Set<String> parts = new HashSet<>();
        for (Element slide : slides) {
            String part = internal("ppt/presentation.xml", relations.get(attribute(slide, "id")));
            if (!parts.add(part)) throw failure(MALFORMED);
            String location = "Slide " + ++number;
            paragraphs(part, location, "hlinkClick");
            for (Relation relation : relations(part).values()) {
                if (!relation.external && relation.type.endsWith("/notesSlide")) {
                    paragraphs(internal(part, relation), location + " notes", "hlinkClick");
                }
            }
        }
    }

    private void paragraphs(String part, String label, String hyperlinkName) throws IOException {
        Document document = xml(part);
        Map<String, Relation> relations = relations(part);
        int number = 0;
        for (Element paragraph : elements(document, "p")) {
            String location = label + ", paragraph " + ++number;
            links.text(text(paragraph, "t"), location);
            // Word field instructions and spreadsheet-style quoted URLs remain inert text.
            links.text(text(paragraph, "instrText"), location);
            for (Element field : elements(paragraph, "fldSimple")) links.text(attribute(field, "instr"), location);
            for (Element hyperlink : elements(paragraph, hyperlinkName)) external(relations, attribute(hyperlink, "id"), location);
            for (Element hyperlink : elements(paragraph, "hlinkMouseOver")) external(relations, attribute(hyperlink, "id"), location);
        }
        // Slide shapes can carry a click target outside a text paragraph.
        if ("hlinkClick".equals(hyperlinkName)) {
            int shape = 0;
            for (Element properties : elements(document, "cNvPr")) {
                String location = label + ", shape " + ++shape;
                for (Element hyperlink : elements(properties, "hlinkClick")) external(relations, attribute(hyperlink, "id"), location);
                for (Element hyperlink : elements(properties, "hlinkMouseOver")) external(relations, attribute(hyperlink, "id"), location);
            }
        }
    }

    private void external(Map<String, Relation> relations, String id, String location) {
        if (id.isEmpty()) return;
        Relation relation = relations.get(id);
        if (relation == null) throw failure(MALFORMED);
        if (relation.external && relation.type.endsWith("/hyperlink")) links.target(relation.target, location);
    }

    private Map<String, Relation> relations(String part) throws IOException {
        int slash = part.lastIndexOf('/');
        String name = part.substring(0, slash + 1) + "_rels/" + part.substring(slash + 1) + ".rels";
        var result = new HashMap<String, Relation>();
        if (zip.getEntry(name) == null) return result;
        for (Element element : elements(xml(name), "Relationship")) {
            String id = element.getAttribute("Id");
            var relation = new Relation(element.getAttribute("Target"), "External".equals(element.getAttribute("TargetMode")),
                    element.getAttribute("Type"));
            if (id.isEmpty() || result.put(id, relation) != null) throw failure(MALFORMED);
        }
        return result;
    }

    private String internal(String part, Relation relation) {
        if (relation == null || relation.external) throw failure(MALFORMED);
        try {
            var target = java.net.URI.create("/" + part).resolve(relation.target).normalize();
            String path = target.getPath();
            if (target.isAbsolute() || target.getAuthority() != null || target.getQuery() != null
                    || target.getFragment() != null || path == null || !path.startsWith("/") || path.contains("/../")) throw failure(MALFORMED);
            return path.substring(1);
        } catch (IllegalArgumentException exception) { throw failure(MALFORMED); }
    }

    private Document xml(String name) throws IOException {
        links.checkTime();
        var entry = zip.getEntry(name);
        if (entry == null) throw failure(MALFORMED);
        try (InputStream input = zip.getInputStream(entry)) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException exception) throws SAXException { throw exception; }
                @Override public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException { throw exception; }
            });
            return builder.parse(input);
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException exception) { throw failure(MALFORMED); }
    }

    private static List<Element> elements(Node node, String name) {
        var nodes = node instanceof Document document ? document.getElementsByTagNameNS("*", name)
                : ((Element) node).getElementsByTagNameNS("*", name);
        var result = new ArrayList<Element>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        return result;
    }

    private static String text(Element node, String name) {
        var text = new StringBuilder();
        for (Element element : elements(node, name)) text.append(element.getTextContent());
        return text.toString();
    }

    private static String attribute(Element element, String local) {
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (local.equals(attribute.getLocalName()) && (!"id".equals(local) || attribute.getNamespaceURI() != null)) {
                return attribute.getNodeValue();
            }
        }
        return "";
    }

    private record Relation(String target, boolean external, String type) {}

    private static void admit(ZipFile zip, OfflineGoogleDriveLinkReader.Links links) throws IOException {
        var admission = new Admission(links);
        Set<String> names = new HashSet<>();
        try {
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // Keep JAXP bounded while allowing Admission to report the typed depth-100 limit first.
            parser.setProperty("jdk.xml.maxElementDepth", "101");
            byte[] buffer = new byte[16_384];
            CRC32 checksum = new CRC32();
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                links.checkTime();
                if (!names.add(entry.getName()) || names.size() > 10_000) throw failure(LIMIT_EXCEEDED);
                admission.expanded = 0;
                checksum.reset();
                try (InputStream input = zip.getInputStream(entry)) {
                    InputStream bounded = admission.wrap(new CheckedInputStream(input, checksum));
                    if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels")) parser.parse(bounded, admission);
                    else while (bounded.read(buffer) != -1) { /* Admit images and other opaque parts too. */ }
                }
                if (admission.expanded != entry.getSize() || checksum.getValue() != entry.getCrc()) throw failure(MALFORMED);
                if (admission.expanded > 1_048_576 && (entry.getCompressedSize() < 1
                        || admission.expanded > entry.getCompressedSize() * 100)) throw failure(LIMIT_EXCEEDED);
            }
            if (!names.contains("[Content_Types].xml")) throw failure(MALFORMED);
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof GoogleDriveProviderException provider) throw provider;
            }
            throw failure(MALFORMED);
        }
    }

    private static final class Admission extends DefaultHandler {
        private final OfflineGoogleDriveLinkReader.Links links;
        private long total;
        private long expanded;
        private long text;
        private int nodes;
        private int depth;
        private int cells;
        private int strings;
        private int rows;

        Admission(OfflineGoogleDriveLinkReader.Links links) { this.links = links; }

        InputStream wrap(InputStream input) {
            return new java.io.FilterInputStream(input) {
                @Override public int read() throws IOException {
                    int value = in.read();
                    count(value < 0 ? 0 : 1);
                    return value;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    int value = in.read(buffer, offset, length);
                    count(Math.max(0, value));
                    return value;
                }
                @Override public void close() { /* ZIP entry owner closes the stream. */ }
            };
        }

        private void count(int bytes) {
            expanded += bytes;
            total += bytes;
            links.checkTime();
            if (total > 64L * 1024 * 1024) throw failure(LIMIT_EXCEEDED);
        }

        @Override public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
            if (++nodes > 2_000_000 || ++depth > 100
                    || "c".equals(local) && ++cells > StructuredContent.MAX_CELLS
                    || "si".equals(local) && ++strings > StructuredContent.MAX_CELLS
                    || "row".equals(local) && ++rows > StructuredContent.MAX_CELLS) throw new SAXException(failure(LIMIT_EXCEEDED));
        }
        @Override public void endElement(String uri, String local, String name) { depth--; }
        @Override public void characters(char[] chars, int start, int length) throws SAXException {
            if ((text += length) > NativeSnapshot.MAX_RAW_TEXT) throw new SAXException(failure(LIMIT_EXCEEDED));
        }
        @Override public void error(org.xml.sax.SAXParseException exception) throws SAXException { throw exception; }
        @Override public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException { throw exception; }
    }
}
