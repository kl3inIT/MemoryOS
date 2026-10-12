package io.memoryos.chat.interpreter;

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
 * The plain calls of the memoryos-interpreter service. The client behind it carries the base URL, the API key, the
 * call's deadline and its bound. An answer is null when the service sent a success with no body.
 */
@HttpExchange
interface InterpreterApi {
    /** The part's headers carry the file's content type; its name comes from the resource. */
    @PostExchange(url = "/v1/files", contentType = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Nullable JsonNode upload(@RequestPart("file") HttpEntity<Resource> file);

    /** A tree, read by the same code that reads the streamed run's result event. */
    @PostExchange("/v1/execute")
    @Nullable JsonNode execute(@RequestBody Map<String, Object> run);

    @GetExchange("/v1/files/{id}")
    byte @Nullable [] download(@PathVariable String id);

    @DeleteExchange("/v1/files/{id}")
    void delete(@PathVariable String id);
}
