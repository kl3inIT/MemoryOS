package io.memoryos.ai.systemone;

import io.memoryos.shared.OutboundHttp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * The {@code POST /systemone} protocol TypeSafe published and its gateways and open servers speak, through the
 * TypeSafe SDK (ADR 0025, choice 1). The {@link RestClient} is built here and handed to the SDK, so the four
 * transport rules hold: no redirect, one deadline, a bounded answer, and a failed answer reported by its status with
 * the body unread. The SDK's own error handler, which reads that body into the exception message, is never installed,
 * and a connection without a key sends no {@code Authorization} header.
 */
public final class SystemOneProtocol {
    /** The id of the one question a request carries; answers come back under it. */
    public static final String QUESTION = "decision";
    /** A choice answer is a label and a probability per label; 64 KiB is far past the largest. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private SystemOneProtocol() {}

    /**
     * @param base the address the paths are appended to, ending in the version path ({@code https://host/v1})
     * @param key  the bearer key, empty for none
     */
    public static SystemOneClient.Decision choose(String base, String key, String model, SystemOneClient.Question question,
                                                  Duration timeout) {
        RestClient http = OutboundHttp.builder(new OutboundHttp.Limits(timeout, MAX_RESPONSE_BYTES))
                .baseUrl(base.replaceAll("/+$", ""))
                .defaultHeaders(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                    if (!key.isEmpty()) headers.setBearerAuth(key);
                }).build();
        var client = new TypeSafeClient(new TypeSafeApi("/systemone", "/models", http), model, RetryPolicy.noRetry());
        var response = client.systemOne(request(question));
        var answer = response.choice(QUESTION);
        return new SystemOneClient.Decision(answer.value(), answer.confidence(), answer.probabilities(),
                tokens(response.usage().inputTokens()), tokens(response.usage().outputTokens()));
    }

    public static SystemOneRequest request(SystemOneClient.Question question) {
        var choice = Choice.builder().instructions(question.instructions());
        question.options().forEach(choice::option);
        return SystemOneRequest.builder().state(question.state()).questions(Map.of(QUESTION, choice.build())).build();
    }

    static @Nullable Long tokens(@Nullable Integer count) {
        return count == null ? null : count.longValue();
    }
}
