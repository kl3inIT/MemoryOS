package io.memoryos.provider.google;

import io.memoryos.connector.GoogleDriveProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@EnableConfigurationProperties(GoogleDriveProviderProperties.class)
public class GoogleDriveProviderAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(GoogleDriveProvider.class)
    RestGoogleDriveProvider googleDriveProvider(GoogleDriveProviderProperties properties, ObjectMapper mapper) {
        return new RestGoogleDriveProvider(properties, mapper);
    }
}
