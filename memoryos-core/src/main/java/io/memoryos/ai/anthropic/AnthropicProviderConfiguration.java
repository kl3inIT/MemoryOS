package io.memoryos.ai.anthropic;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Anthropic Messages protocol. Its clients are built per catalog provider; the deployment holds no model key. */
@Configuration(proxyBeanMethods = false)
public class AnthropicProviderConfiguration {
    @Bean
    public AnthropicProviderAdapter anthropicChatProviderAdapter(ObservationRegistry observations, MeterRegistry meters) {
        return new AnthropicProviderAdapter(observations, meters);
    }
}
