package io.memoryos.integration.whatsapp;

import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(WhatsAppProviderProperties.class)
public class WhatsAppProviderAutoConfiguration {
    /**
     * MEM-283: the WhatsApp bot. The API checks a bot's number with it; only the process that receives opens one, and
     * only then is the bridge started, with the database this process uses.
     */
    @Bean(destroyMethod = "close")
    WhatsAppChannelAdapter whatsAppChannelAdapter(WhatsAppProviderProperties properties,
                                                  ObjectProvider<DataSourceProperties> database) {
        var bridge = new WhatsAppBridge(Path.of(properties.bridgePath()), properties.bridgeLogLevel(), () -> {
            var configured = database.getObject();
            return WhatsAppDatabase.address(configured.determineUrl(), configured.determineUsername(),
                    configured.determinePassword());
        });
        return new WhatsAppChannelAdapter(bridge);
    }
}
