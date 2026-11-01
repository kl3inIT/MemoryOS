package io.memoryos.chat.image;

import io.memoryos.chat.image.adapter.CloudflareWorkersAiImageAdapter;
import io.memoryos.chat.image.adapter.CustomHttpImageAdapter;
import io.memoryos.chat.image.adapter.FalImageAdapter;
import io.memoryos.chat.image.adapter.GeminiImageAdapter;
import io.memoryos.chat.image.adapter.OpenAiImageAdapter;
import io.memoryos.chat.image.adapter.OpenRouterImageAdapter;
import io.memoryos.chat.image.adapter.XaiImageAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** The image registry and client as the application wires them, with observations recorded as timers in {@code meters}. */
final class ImageClients {
    private ImageClients() {}

    static ImageAdapterRegistry registry() {
        return new ImageAdapterRegistry(List.of(new OpenAiImageAdapter(), new CloudflareWorkersAiImageAdapter(),
                new GeminiImageAdapter(), new OpenRouterImageAdapter(), new XaiImageAdapter(), new FalImageAdapter(), new CustomHttpImageAdapter()));
    }

    static ImageProviderClient client(ImageHttp http, ImageConnectionService connections, MeterRegistry meters) {
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        return new ImageProviderClient(http, connections, registry(), new ObjectMapper(), observations, null);
    }
}
