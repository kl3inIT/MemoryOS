package io.memoryos.connector.adapter.googledrive;

import io.memoryos.connector.GoogleDriveAccountClient;
import io.memoryos.connector.GoogleDriveLinkReader;
import io.memoryos.connector.GoogleDriveGateway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

@AutoConfiguration
@EnableConfigurationProperties(GoogleDriveProviderProperties.class)
public class GoogleDriveProviderAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(GoogleDriveGateway.class)
    RestGoogleDriveGateway googleDriveProvider(GoogleDriveProviderProperties properties, ObjectMapper mapper) {
        return new RestGoogleDriveGateway(properties, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(GoogleDriveAccountClient.class)
    RestGoogleDriveAccountClient googleDriveAccountClient(GoogleDriveProviderProperties properties, ObjectMapper mapper) {
        return new RestGoogleDriveAccountClient(properties, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(GoogleDriveLinkReader.class)
    GoogleDriveLinkReader googleDriveLinkReader(ObjectMapper mapper) {
        return new OfflineGoogleDriveLinkReader(mapper);
    }
}
