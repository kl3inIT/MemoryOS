import { appText } from "@/i18n/app-text";
import { testEmbeddingProvider } from "@/lib/hey-api/sdk.gen";
import type {
  EmbeddingProviderTestRequest,
  EmbeddingProviderTestResponse,
} from "@/lib/hey-api/types.gen";
import { useConnectionTest, type ProviderTestOutcome } from "@/features/models/provider-test";
import { searchSettingsError } from "./search-settings";

/** The returned model, its real dimensions and the round trip; a failed call carries the provider's own reason. */
export function embeddingTestOutcome(result: EmbeddingProviderTestResponse): ProviderTestOutcome {
  if (!result.ok)
    return {
      ok: false,
      message: appText("Kết nối thất bại · {{latency}} ms", { latency: result.latencyMs }),
      detail: result.error ?? undefined,
    };
  return {
    ok: true,
    message: appText("Kết nối được · {{model}} · {{dimensions}} chiều · {{latency}} ms", {
      model: result.model ?? "—",
      dimensions: result.dimensions ?? "—",
      latency: result.latencyMs,
    }),
  };
}

/** One real `/v1/embeddings` call through the server; a typed key is sent once and never kept. */
export function useEmbeddingTest() {
  return useConnectionTest(
    async (body: EmbeddingProviderTestRequest, signal) => {
      const { data } = await testEmbeddingProvider({
        body,
        signal,
      });
      return embeddingTestOutcome(data);
    },
    (cause) => searchSettingsError(cause, "test"),
  );
}
