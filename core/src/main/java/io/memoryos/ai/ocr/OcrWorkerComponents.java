package io.memoryos.ai.ocr;

import io.memoryos.ai.ProviderConnections;
import io.memoryos.ai.ProviderCredentials;
import org.springframework.context.annotation.Import;

/**
 * What the Worker needs to read a Tenant's OCR reader for each document: the connection service and the credential
 * handling it shares with every provider connection. The Worker imports this class; it is deliberately not a component,
 * so the API, which scans all of {@code io.memoryos}, does not register these twice.
 */
@Import({OcrConnectionService.class, ProviderConnections.class, ProviderCredentials.class})
public class OcrWorkerComponents {
}
