package io.memoryos.objectstorage.s3;

import io.memoryos.objectstorage.application.ObjectUploadProperties;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({S3ObjectStorageProperties.class, ObjectUploadProperties.class})
class S3ObjectStorageConfiguration {

    @Bean(destroyMethod = "close")
    S3ObjectStorage s3ObjectStorage(S3ObjectStorageProperties properties) {
        return S3ObjectStorage.create(properties, Clock.systemUTC());
    }
}
