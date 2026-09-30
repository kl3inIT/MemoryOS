package io.memoryos.connector.adapter.sharepoint;

import io.memoryos.connector.SharePointGateway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@EnableConfigurationProperties(SharePointProviderProperties.class)
public class SharePointProviderAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SharePointGateway.class)
    RestSharePointGateway sharePointProvider(SharePointProviderProperties properties, ObjectMapper mapper,
            MeterRegistry registry) {
        return new RestSharePointGateway(properties, mapper, registry);
    }
}
