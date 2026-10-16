package io.memoryos.integration.zalo;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * The Zalo Bot Platform's calls. The client behind it carries {@code https://bot-api.zaloplatforms.com/bot<token>}, so a
 * method names only its own path; every answer is the envelope {@code {ok, result, description, error_code}}, read as a
 * tree because Zalo documents its fields loosely.
 */
@HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE, contentType = MediaType.APPLICATION_JSON_VALUE)
interface ZaloBotApi {
    @PostExchange("/getMe")
    @Nullable JsonNode getMe(@RequestBody Map<String, Object> body);

    @PostExchange("/getUpdates")
    @Nullable JsonNode getUpdates(@RequestBody Map<String, Object> body);

    @PostExchange("/setWebhook")
    @Nullable JsonNode setWebhook(@RequestBody Map<String, Object> body);

    @PostExchange("/testWebhook")
    @Nullable JsonNode testWebhook(@RequestBody Map<String, Object> body);

    @PostExchange("/deleteWebhook")
    @Nullable JsonNode deleteWebhook(@RequestBody Map<String, Object> body);

    @PostExchange("/getWebhookInfo")
    @Nullable JsonNode getWebhookInfo(@RequestBody Map<String, Object> body);

    @PostExchange("/sendMessage")
    @Nullable JsonNode sendMessage(@RequestBody Map<String, Object> body);

    @PostExchange("/sendChatAction")
    @Nullable JsonNode sendChatAction(@RequestBody Map<String, Object> body);
}
