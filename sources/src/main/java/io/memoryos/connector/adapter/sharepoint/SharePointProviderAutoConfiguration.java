package io.memoryos.connector.adapter.sharepoint;

import io.memoryos.connector.SharePointProvider;
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
    @ConditionalOnMissingBean(SharePointProvider.class)
    RestSharePointProvider sharePointProvider(SharePointProviderProperties properties, ObjectMapper mapper,
            MeterRegistry registry) {
        return new RestSharePointProvider(properties, mapper, registry);
    }
}
