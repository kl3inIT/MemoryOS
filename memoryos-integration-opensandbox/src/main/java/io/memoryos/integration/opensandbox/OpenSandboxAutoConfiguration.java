package io.memoryos.integration.opensandbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** The OpenSandbox runtime, where the deployment names a server (MEM-270). */
@AutoConfiguration
@EnableConfigurationProperties(OpenSandboxProperties.class)
public class OpenSandboxAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("'${memoryos.sandbox.opensandbox.endpoint:}' != ''")
    OpenSandboxRuntime openSandboxRuntime(OpenSandboxProperties properties) {
        return new OpenSandboxRuntime(properties);
    }
}
