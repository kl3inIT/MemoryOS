import { useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { handleAuthorizationFailure } from "@/lib/query-client";
import { isCatalogConflict, modelActionError } from "./model-catalog";

/** Direct SDK operations never retain request bodies or raw provider errors in a cache. */
export function useModelAction() {
  const client = useQueryClient();
  const controller = useRef<AbortController | null>(null);
  const active = useRef(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);

  function cancel() {
    controller.current?.abort();
    controller.current = null;
    setPending(false);
    setError(null);
  }

  useLayoutEffect(() => {
    active.current = true;
    const leave = () => {
      active.current = false;
      cancel();
    };
    const restore = () => {
      active.current = true;
    };
    window.addEventListener("pagehide", leave);
    window.addEventListener("pageshow", restore);
    return () => {
      active.current = false;
      controller.current?.abort();
      controller.current = null;
      window.removeEventListener("pagehide", leave);
      window.removeEventListener("pageshow", restore);
    };
  }, []);

  async function run(task: (signal: AbortSignal) => Promise<void>) {
    if (!active.current || controller.current)
      throw new Error("An action is already in progress or this page is inactive.");
    const operation = new AbortController();
    controller.current = operation;
    setPending(true);
    setError(null);
    try {
      await task(operation.signal);
      operation.signal.throwIfAborted();
    } catch (cause) {
      if (!operation.signal.aborted && active.current) {
        handleAuthorizationFailure(client, cause);
        setError(modelActionError(cause));
        if (isCatalogConflict(cause)) setConflict(true);
      }
      // Never let an SDK cause (which can include a request) escape into retained UI state.
      throw new Error(
        operation.signal.aborted ? "The operation was discarded." : modelActionError(cause),
      );
    } finally {
      if (controller.current === operation) {
        controller.current = null;
        setPending(false);
      }
    }
  }

  return { pending, error, conflict, run, cancel, reconciled: () => setConflict(false) };
}
