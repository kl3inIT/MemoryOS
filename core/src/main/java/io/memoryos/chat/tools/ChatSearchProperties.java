package io.memoryos.chat.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("memoryos.chat.search")
public record ChatSearchProperties(@DefaultValue("12") int candidates, @DefaultValue("5") int sections,
                                   @DefaultValue("6000") int selectionTokens, @DefaultValue("8000") int contextTokens,
                                   @DefaultValue("3") int maxCalls) {
    public ChatSearchProperties {
        if (candidates > 30 || sections < 1 || sections > 10 || sections > candidates
                || selectionTokens < 512 || selectionTokens > 16000 || contextTokens < 512 || contextTokens > 32000
            || maxCalls < 1 || maxCalls > 6) throw new IllegalArgumentException("Invalid Chat search limits");
    }

    /** Two cached rewrites, then selection and per-section classification; at most two native binding attempts. */
    public int helperCallLimit() { return 2 * (2 + maxCalls * (1 + sections)); }
}
