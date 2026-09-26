import { useRef, useState } from "react";
import { appText, type AppCopy, type AppText } from "@/i18n/app-text";
import { testChatProvider } from "@/lib/hey-api/sdk.gen";
import type { ProviderTestInput } from "@/lib/hey-api/types.gen";
import { modelActionError } from "./model-catalog";

export type ProviderTestOutcome = { ok: boolean; message: AppCopy; detail?: string };

/** Northstar's gateway test line: the outcome, its round trip and what the endpoint serves. */
function providerTestSuccess(
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

/**
 * One connection check at a time: a newer check or a reset discards the older outcome. The request body can
 * carry a key, so it is sent once and never retained here.
 */
export function useConnectionTest<Body>(
  request: (body: Body, signal: AbortSignal) => Promise<ProviderTestOutcome>,
  describe: (cause: unknown) => AppCopy = modelActionError,
) {
  const controller = useRef<AbortController | null>(null);
  const [pending, setPending] = useState(false);
  const [outcome, setOutcome] = useState<ProviderTestOutcome | null>(null);

  async function run(body: Body) {
    controller.current?.abort();
    const operation = new AbortController();
    controller.current = operation;
    setPending(true);
    setOutcome(null);
    try {
      const result = await request(body, operation.signal);
      if (!operation.signal.aborted) setOutcome(result);
    } catch (cause) {
      if (!operation.signal.aborted) setOutcome({ ok: false, message: describe(cause) });
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

/** Checks an endpoint and key by listing its models. */
export function useProviderTest() {
  return useConnectionTest(async (body: ProviderTestInput, signal) => {
    const { data } = await testChatProvider({
      body,
      signal,
    });
    return { ok: true, message: providerTestSuccess(data.latencyMillis, data.modelCount) };
  });
}
