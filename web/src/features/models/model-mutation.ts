import { useIsMutating, useMutation } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { isCatalogConflict, sanitizeModelActionError } from "./model-catalog";

/** Every catalog write, validation and reconciliation on the Models page shares this key. */
export const modelCatalogMutationKey = ["models", "catalog-action"] as const;

const discarded = "The operation was discarded.";

/** True while any catalog action on the page is in flight; controls refuse a second one meanwhile. */
export function useModelCatalogBusy() {
  return useIsMutating({ mutationKey: modelCatalogMutationKey }) > 0;
}

function rejectOnAbort(signal: AbortSignal) {
  return new Promise<never>((_, reject) => {
    if (signal.aborted) reject(signal.reason);
    else signal.addEventListener("abort", () => reject(signal.reason), { once: true });
  });
}

/**
 * One catalog operation as a TanStack mutation. The operation reads its inputs from the render that started it, so
 * request bodies and provider keys never become mutation variables; the only variable is the operation's AbortSignal,
 * because a mutation function receives none. The mutation settles with a sanitized error and is collected as soon as
 * nothing observes it. Cancelling or unmounting aborts the operation and settles it at once as discarded, so a late
 * response is never applied and never holds the page busy. `describe` gives a failure the page's own static copy.
 *
 * The generated `…Mutation()` factories are not used for catalog writes on purpose: their variables are the whole
 * request, and a provider or embedding request can carry a typed API key, which the mutation cache would retain.
 */
export function useModelMutation(
  task: (signal: AbortSignal) => Promise<void>,
  { onConflict, describe }: { onConflict?: () => void; describe?: (cause: unknown) => string } = {},
) {
  const controller = useRef<AbortController | null>(null);
  const mutation = useMutation<void, Error, AbortSignal>({
    mutationKey: modelCatalogMutationKey,
    gcTime: 0,
    mutationFn: async (signal) => {
      try {
        signal.throwIfAborted();
        await Promise.race([task(signal), rejectOnAbort(signal)]);
        signal.throwIfAborted();
      } catch (cause) {
        throw signal.aborted ? new Error(discarded) : sanitizeModelActionError(cause, describe);
      }
    },
  });

  useEffect(
    () => () => {
      controller.current?.abort();
      controller.current = null;
    },
    [],
  );

  async function run() {
    if (controller.current) throw new Error("An action is already in progress.");
    const operation = new AbortController();
    controller.current = operation;
    try {
      await mutation.mutateAsync(operation.signal);
    } catch (error) {
      if (!operation.signal.aborted && isCatalogConflict(error)) onConflict?.();
      throw error;
    } finally {
      if (controller.current === operation) controller.current = null;
    }
  }

  /** Abandons the operation in flight, if any, and clears its feedback. */
  function cancel() {
    controller.current?.abort();
    controller.current = null;
    mutation.reset();
  }

  return {
    pending: mutation.isPending,
    error: mutation.error?.message ?? null,
    run,
    cancel,
  };
}
