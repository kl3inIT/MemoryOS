import { getSourceOperation } from "@/lib/hey-api/sdk.gen";
import type { SourceOperation } from "@/lib/hey-api/types.gen";

export const terminalOperationStatuses = {
  SUCCEEDED: true,
  SUPERSEDED: true,
  FAILED: true,
} as const;

export async function waitForSourceOperation(operation: SourceOperation, signal: AbortSignal) {
  while (!Object.hasOwn(terminalOperationStatuses, operation.status)) {
    await abortableDelay(1_500, signal);
    const response = await getSourceOperation({
      path: { operationId: operation.id },
      signal,
      throwOnError: true,
    });
    operation = response.data;
  }
  signal.throwIfAborted();
  return operation;
}

export function abortableDelay(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(signal.reason);
      return;
    }
    const timeout = window.setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, milliseconds);
    function abort() {
      window.clearTimeout(timeout);
      reject(signal.reason);
    }
    signal.addEventListener("abort", abort, { once: true });
  });
}
