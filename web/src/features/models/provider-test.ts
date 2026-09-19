import { useRef, useState } from "react";
import { appText, type AppCopy, type AppText } from "@/i18n/app-text";
import { sameOriginMutationHeaders } from "@/lib/api";
import { testChatProvider } from "@/lib/hey-api/sdk.gen";
import type { ProviderTestInput } from "@/lib/hey-api/types.gen";
import { modelActionError } from "./model-catalog";

export type ProviderTestOutcome = { ok: boolean; message: AppCopy };

/** Northstar's gateway test line: the outcome, its round trip and what the endpoint serves. */
export function providerTestSuccess(
  latencyMillis: number,
  modelCount: number | null | undefined,
): AppText {
  return modelCount == null
    ? appText("Connection succeeded · {{latency}} ms", { latency: latencyMillis })
    : appText("Connection succeeded · {{latency}} ms · {{count}} models", {
        latency: latencyMillis,
        count: modelCount,
      });
}

/** Checks an endpoint and key by listing its models; the key is sent once and never retained here. */
export function useProviderTest() {
  const controller = useRef<AbortController | null>(null);
  const [pending, setPending] = useState(false);
  const [outcome, setOutcome] = useState<ProviderTestOutcome | null>(null);

  async function run(body: ProviderTestInput) {
    controller.current?.abort();
    const operation = new AbortController();
    controller.current = operation;
    setPending(true);
    setOutcome(null);
    try {
      const { data } = await testChatProvider({
        body,
        headers: sameOriginMutationHeaders,
        signal: operation.signal,
        throwOnError: true,
      });
      if (!operation.signal.aborted)
        setOutcome({ ok: true, message: providerTestSuccess(data.latencyMillis, data.modelCount) });
    } catch (cause) {
      if (!operation.signal.aborted) setOutcome({ ok: false, message: modelActionError(cause) });
    } finally {
      if (controller.current === operation) {
        controller.current = null;
        setPending(false);
      }
    }
  }

  function reset() {
    controller.current?.abort();
    controller.current = null;
    setPending(false);
    setOutcome(null);
  }

  return { pending, outcome, run, reset };
}
