package io.memoryos.ai.openai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One supported protocol, using native Spring AI clients and Embabel service/options. Its clients are built per
 * catalog provider; the deployment holds no model key of its own (MEM-211).
 */
@Configuration(proxyBeanMethods = false)
public class OpenAiProviderConfiguration {
    @Bean
    public OpenAiProviderAdapter openAiChatProviderAdapter(ObservationRegistry observations, MeterRegistry meters) {
        return new OpenAiProviderAdapter(observations, meters);
    }
}
