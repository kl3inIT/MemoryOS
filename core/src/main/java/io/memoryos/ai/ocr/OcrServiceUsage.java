package io.memoryos.ai.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Counts the pages an OCR service read, priced by the call from the hand-kept {@code ocr/known-services.json}: such a
 * service bills a call, not tokens, so the token catalog regenerated from LiteLLM does not hold it. A service nobody
 * listed is counted with an unknown cost, never an invented one.
 */
@Service
public class OcrServiceUsage {
    private static final Logger LOG = LoggerFactory.getLogger(OcrServiceUsage.class);
    private static final String RESOURCE = "/ocr/known-services.json";
    private final Map<OcrProvider, Double> pricePerCall = prices();
    private final @Nullable AiUsageRecorder usage;

    public OcrServiceUsage(ObjectProvider<AiUsageRecorder> usage) { this.usage = usage.getIfAvailable(); }

    /** One page read by the connection's service. A ledger that refuses the entry does not take the reading with it. */
    public void page(OcrConnectionService.Connection connection) {
        if (usage == null) return;
        try {
            usage.record(new AiUsage(connection.tenantId(), null, AiUsageFlow.OCR, connection.provider().name(),
                    connection.provider().name(), null, null, connection.dataBoundary().name(), 1, 0, 0, 0, 1, 0,
                    pricePerCall.get(connection.provider()), Instant.now()));
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "ocr.page.usage_not_recorded")
                    .addKeyValue("error_type", failure.getClass().getName()).log("OCR page usage not recorded");
        }
    }

    private static Map<OcrProvider, Double> prices() {
        try (InputStream stream = OcrServiceUsage.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing OCR service prices " + RESOURCE);
            var prices = new EnumMap<OcrProvider, Double>(OcrProvider.class);
            for (JsonNode service : new ObjectMapper().readTree(stream).path("ocr")) {
                JsonNode price = service.path("pricePerThousandCalls");
                if (price.isNumber() && price.asDouble() >= 0)
                    prices.put(OcrProvider.valueOf(service.path("provider").asText()), price.asDouble() / 1000);
            }
            return prices;
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new IllegalStateException("Unreadable OCR service prices " + RESOURCE, unreadable);
        }
    }
}
