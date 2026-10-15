package io.memoryos.integration.zalo;

import io.memoryos.channel.ChannelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ZaloProviderProperties.class)
public class ZaloProviderAutoConfiguration {
    /**
     * MEM-263: the Zalo bot. The API checks a bot's token with it; only the process that receives opens one. The public
     * address is the channel capability's, absent in a process that runs no channel.
     */
    @Bean(destroyMethod = "close")
    ZaloChannelAdapter zaloChannelAdapter(ZaloProviderProperties properties, ObjectProvider<ChannelProperties> channel) {
        return new ZaloChannelAdapter(properties.baseUrl(), () -> {
            var configured = channel.getIfAvailable();
            return configured == null ? "" : configured.webUrl();
        });
    }
}
