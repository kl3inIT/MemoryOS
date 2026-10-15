package io.memoryos.channels;

import io.memoryos.channel.ChannelRuntimeComponents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Holds every bot's connection open and answers what it receives with a Chat turn run in this process. It scans
 * only its own package and runs what the channel capability publishes for it, as the Worker runs what each module
 * publishes; it serves no HTTP API, only its health and the address platforms push a bot's messages to.
 */
@SpringBootApplication
@Import(ChannelRuntimeComponents.class)
public class MemoryOsChannelsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemoryOsChannelsApplication.class, args);
    }
}
