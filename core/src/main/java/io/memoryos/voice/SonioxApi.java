package io.memoryos.voice;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * Soniox's async transcription API. The client behind it carries the connection's base URL and key, so a method names
 * only what the call itself needs. An answer is null when the provider sent a success with no body.
 */
@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE)
interface SonioxApi {
    /** The part's headers carry the audio's content type; its name comes from the resource. */
    @PostExchange(url = "/files", contentType = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Nullable Created upload(@RequestPart("file") HttpEntity<Resource> audio);

    @PostExchange("/transcriptions")
    @Nullable Created transcribe(@RequestBody Map<String, Object> transcription);

    @GetExchange("/transcriptions/{id}")
    @Nullable Status status(@PathVariable String id);

    /** A tree, because each token is read with a default for every field it may lack. */
    @GetExchange("/transcriptions/{id}/transcript")
    @Nullable JsonNode transcript(@PathVariable String id);

    @DeleteExchange("/transcriptions/{id}")
    void deleteTranscription(@PathVariable String id);

    @DeleteExchange("/files/{id}")
    void deleteFile(@PathVariable String id);

    record Created(@Nullable String id) {}
    record Status(@Nullable String status) {}
}
