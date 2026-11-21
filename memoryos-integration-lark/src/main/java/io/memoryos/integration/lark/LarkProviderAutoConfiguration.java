package io.memoryos.integration.lark;

import io.memoryos.connector.LarkGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@EnableConfigurationProperties(LarkProviderProperties.class)
public class LarkProviderAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(LarkGateway.class)
    RestLarkGateway larkProvider(LarkProviderProperties properties, MeterRegistry registry) {
        return new RestLarkGateway(properties, registry);
    }

    @Bean
    LarkDocxSourceContentExtractor larkDocxSnapshotReader(ObjectMapper mapper) {
        return new LarkDocxSourceContentExtractor(mapper);
    }

    @Bean
    LarkSheetSourceContentExtractor larkSheetSnapshotReader(ObjectMapper mapper) {
        return new LarkSheetSourceContentExtractor(mapper);
    }
}
