import { useQuery, type QueryKey, type UseQueryOptions } from "@tanstack/react-query";
import { useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError } from "@/lib/api";
import { getSourceOperationOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SourceOperation } from "@/lib/hey-api/types.gen";
import { terminalOperationStatuses } from "./source-operations";

export type SelectionReceipt = { sourceId: string; operation: SourceOperation };

/**
 * Tracks one accepted-but-unverified selection request: its request identifier survives a reload
 * in session storage, a receipt is recovered when the answer was lost, and the operation is polled
 * until it settles. Connectors differ only in which endpoint recovers a receipt.
 */
export function useSourceSelectionOperation<
  Receipt extends SelectionReceipt,
  Key extends QueryKey,
  Err = Error,
>({
  provider,
  scope,
  recover,
  pending,
}: {
  provider: string;
  scope: string;
  recover: (requestId: string) => UseQueryOptions<Receipt, Err, Receipt, Key>;
  pending?: SourceOperation | null;
}) {
  const session = useApplicationSession();
  const storageKey = `memoryos:${provider}-selection:${session.actorId}:${scope}`;
  const [requestId, setRequestId] = useState<string | null>(() => {
    try {
      const value = sessionStorage.getItem(storageKey);
      return value && /^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(value) ? value : null;
    } catch {
      return null;
    }
  });
  const [accepted, setAccepted] = useState<Receipt | null>(null);
  const [submittedHere, setSubmittedHere] = useState(false);
  const recovery = useQuery({
    ...recover(requestId ?? ""),
    enabled: Boolean(requestId && !accepted && !submittedHere),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const receipt = accepted ?? recovery.data;
  const initial = receipt?.operation ?? pending;
  const observation = useQuery({
    ...getSourceOperationOptions({ path: { operationId: initial?.id ?? "" } }),
    enabled: Boolean(initial),
    retry: false,
    refetchInterval: (query) =>
      query.state.data && Object.hasOwn(terminalOperationStatuses, query.state.data.status)
        ? false
        : 1_500,
  });
  const operation = observation.data ?? initial;
  const terminal = Boolean(operation && Object.hasOwn(terminalOperationStatuses, operation.status));
  function begin(newProposal = false) {
    const id = !newProposal && requestId ? requestId : crypto.randomUUID();
    setRequestId(id);
    setAccepted(null);
    setSubmittedHere(true);
    try {
      sessionStorage.setItem(storageKey, id);
    } catch {
      /* Polling still works without browser storage. */
    }
    return id;
  }
  function forget() {
    setRequestId(null);
    setAccepted(null);
    setSubmittedHere(false);
    try {
      sessionStorage.removeItem(storageKey);
    } catch {
      /* Browser storage can be disabled. */
    }
  }
  return {
    requestId,
    receipt,
    operation,
    terminal,
    begin,
    forget,
    accept: setAccepted,
    uncertain: Boolean(requestId && !receipt && submittedHere),
    recovering: Boolean(requestId && !accepted && !submittedHere && recovery.isPending),
    recoveryError: !submittedHere && recovery.isError,
    recoveryMissing: recovery.error instanceof ApiError && recovery.error.status === 404,
    statusUnavailable: observation.isError,
    retryRecovery: () => recovery.refetch(),
    retryStatus: () => observation.refetch(),
  };
}
