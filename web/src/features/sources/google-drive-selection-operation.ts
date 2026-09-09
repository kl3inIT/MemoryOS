import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { ApiError } from "@/lib/api";
import {
  getSourceOperationOptions,
  getGoogleDriveSelectionRequestOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GoogleDriveSelectionReceiptResponse, SourceOperation } from "@/lib/hey-api/types.gen";
import { terminalOperationStatuses } from "./source-operations";

export function useGoogleDriveSelectionOperation(scope: string, pending?: SourceOperation | null) {
  const session = useApplicationSession();
  const storageKey = `memoryos:drive-selection:${session.actorId}:${scope}`;
  const [requestId, setRequestId] = useState<string | null>(() => {
    try {
      const value = sessionStorage.getItem(storageKey);
      return value && /^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(value) ? value : null;
    } catch {
      return null;
    }
  });
  const [accepted, setAccepted] = useState<GoogleDriveSelectionReceiptResponse | null>(null);
  const [submittedHere, setSubmittedHere] = useState(false);
  const recovery = useQuery({
    ...getGoogleDriveSelectionRequestOptions({ path: { requestId: requestId ?? "" } }),
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
