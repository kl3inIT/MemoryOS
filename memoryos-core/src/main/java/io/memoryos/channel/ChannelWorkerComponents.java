package io.memoryos.channel;

import io.memoryos.channel.persistence.JdbcChannelInboundRepository;
import org.springframework.context.annotation.Import;

/**
 * The Channel beans the Worker runs, for a Worker that does not scan Channel: the sweep of old message ids. The
 * Worker imports this class; it is deliberately not a component, so a process that scans all of {@code io.memoryos}
 * registers these beans only once.
 */
@Import({
        JdbcChannelInboundRepository.class,
        ChannelMessageRetention.class
})
public class ChannelWorkerComponents {
}
