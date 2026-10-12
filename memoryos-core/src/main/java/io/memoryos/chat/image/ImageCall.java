package io.memoryos.chat.image;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The transport, parsing and image decoding every image adapter shares. Provider errors and credentials never become
 * model or UI output: a non-2xx answer is only "Image provider request failed".
 */
public final class ImageCall {
    private final ImageHttp http;
    private final ObjectMapper json;

    ImageCall(ImageHttp http, ObjectMapper json) { this.http = http; this.json = json; }

    public JsonNode json(String url, Map<String, String> headers, Map<String, Object> body) throws IOException {
        return parse(http.post(URI.create(url), headers, json.writeValueAsString(body)));
    }

    public JsonNode multipart(String url, Map<String, String> headers, Map<String, String> fields,
                              List<ImageHttp.FilePart> files) throws IOException {
        return parse(http.postMultipart(URI.create(url), headers, fields, files));
    }

    private JsonNode parse(ImageHttp.Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300) throw new IOException("Image provider request failed");
        return json.readTree(response.bytes());
    }

    public static Map<String, String> bearer(String key) { return Map.of("Authorization", "Bearer " + key); }

    public static byte[] base64(String value) throws IOException {
        if (value.isEmpty()) throw new IOException("Image provider returned no image");
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid image encoding"); }
    }

    /** The stored media type follows the returned bytes, not the provider's documentation. */
    public static String mediaType(byte[] bytes) throws IOException {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        throw new IOException("Unsupported image format");
    }
}
