package io.memoryos.chat.image;

import io.memoryos.chat.image.adapter.CloudflareWorkersAiImageAdapter;
import io.memoryos.chat.image.adapter.OpenAiImageAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** The image registry and client as the application wires them, with observations recorded as timers in {@code meters}. */
final class ImageClients {
    private ImageClients() {}

    static ImageAdapterRegistry registry() {
        return new ImageAdapterRegistry(List.of(new OpenAiImageAdapter(), new CloudflareWorkersAiImageAdapter()));
    }

    static ImageProviderClient client(ImageHttp http, ImageConnectionService connections, MeterRegistry meters) {
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return new ImageProviderClient(http, connections, registry(), new ObjectMapper(), observations, null);
    }
}
