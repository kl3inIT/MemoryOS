package io.memoryos.chat.web;

import io.memoryos.chat.web.adapter.BraveWebSearchAdapter;
import io.memoryos.chat.web.adapter.ExaWebAdapter;
import io.memoryos.chat.web.adapter.FirecrawlWebContentAdapter;
import io.memoryos.chat.web.adapter.GooglePseWebSearchAdapter;
import io.memoryos.chat.web.adapter.NineRouterWebAdapter;
import io.memoryos.chat.web.adapter.SearxngWebSearchAdapter;
import io.memoryos.chat.web.adapter.SerperWebSearchAdapter;
import io.memoryos.chat.web.adapter.TavilyWebAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** The Web client as the application wires it: every adapter, and observations recorded as timers in {@code meters}. */
final class WebClients {
    private WebClients() {}

    static List<WebAdapter> adapters() {
        return List.of(new BraveWebSearchAdapter(), new TavilyWebAdapter(), new ExaWebAdapter(), new SerperWebSearchAdapter(),
                new GooglePseWebSearchAdapter(), new SearxngWebSearchAdapter(), new NineRouterWebAdapter(),
                new FirecrawlWebContentAdapter());
    }

    static WebProviderClient client(WebHttp http, WebConnectionService connections, MeterRegistry meters) {
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return new WebProviderClient(http, connections, new WebAdapterRegistry(adapters()), new ObjectMapper(), observations);
    }
}
