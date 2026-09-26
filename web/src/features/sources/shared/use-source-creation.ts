import type { AppCopy } from "@/i18n/app-text";
import { useQueryClient, type QueryKey, type UseQueryOptions } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useEffectEvent, useLayoutEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/api";
import { listSourcesQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { sourceMutationError, sourceStatusMessage } from "./source-errors";
import { useSourceSelectionOperation, type SelectionReceipt } from "./source-selection-operation";

/** Stands in for the request identifier while no request has been made, so a proposal validates. */
export const unsubmittedRequestId = "00000000-0000-4000-8000-000000000000";

/**
 * Creating a provider Source: the request answers at once with a receipt, the provider then
 * verifies the proposal, and the Source opens once its operation succeeds. A request whose answer
 * was lost is retried unchanged under the same request identifier, so it never creates twice.
 */
export function useSourceCreation<
  Body extends { requestId: string },
  Receipt extends SelectionReceipt,
  Key extends QueryKey,
  Err = Error,
>({
  provider,
  recover,
  create,
  failureCode,
  errorKind,
  refresh,
}: {
  provider: string;
  recover: (requestId: string) => UseQueryOptions<Receipt, Err, Receipt, Key>;
  create: (body: Body) => Promise<Receipt>;
  /** Reported when a failed verification carries no error code of its own. */
  failureCode: string;
  errorKind: Parameters<typeof sourceMutationError>[1];
  /** Reads again what the new Source changes, such as the provider's credentials. */
  refresh: () => Promise<unknown>;
}) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const tracking = useSourceSelectionOperation({ provider, scope: "create", recover });
  const [completedOperation, setCompletedOperation] = useState<string | null>(null);
  const [createdSourceId, setCreatedSourceId] = useState<string | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const submitting = useRef(false);
  const submittedProposal = useRef<Body | null>(null);
  const active = useRef(true);
  const pendingValidation = Boolean(tracking.operation && !tracking.terminal);

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);

  const terminalOperation = tracking.terminal ? tracking.operation : null;
  if (terminalOperation && completedOperation !== terminalOperation.id) {
    setCompletedOperation(terminalOperation.id);
    if (terminalOperation.status === "SUCCEEDED") {
      setCreatedSourceId(tracking.receipt?.sourceId ?? null);
    } else {
      setError(
        terminalOperation.status === "SUPERSEDED"
          ? "This creation proposal was superseded or cancelled. No Source was activated by this proposal."
          : sourceStatusMessage(terminalOperation.errorCode ?? failureCode),
      );
    }
  }
  const openCreatedSource = useEffectEvent(() => {
    if (terminalOperation?.status !== "SUCCEEDED") return;
    const targetId = tracking.receipt?.sourceId;
    if (!targetId) return;
    void Promise.all([
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      refresh(),
    ])
      .then(async () => {
        if (!active.current) return;
        await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: targetId } });
        tracking.forget();
      })
      .catch(() => {
        if (active.current)
          setError(
            "The Source was activated but its page could not be opened. Open the created Source; it will not be created twice.",
          );
      });
  });
  useEffect(() => {
    openCreatedSource();
  }, [tracking.operation, tracking.terminal]);

  /**
   * Submits the proposal, or its unchanged predecessor when the last answer was lost. Once the
   * Source exists, opens it instead.
   */
  async function submit(proposal: Omit<Body, "requestId">, ready: boolean) {
    if (submitting.current || pendingValidation || tracking.recovering || tracking.recoveryError)
      return;
    if (createdSourceId) {
      await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: createdSourceId } });
      tracking.forget();
      return;
    }
    if (!tracking.uncertain && !ready) return;
    submitting.current = true;
    setError(null);
    try {
      const requestId = tracking.begin(tracking.terminal);
      const body =
        tracking.uncertain && submittedProposal.current
          ? submittedProposal.current
          : ({ ...proposal, requestId } as Body);
      submittedProposal.current = body;
      const receipt = await create(body);
      if (!active.current) return;
      tracking.accept(receipt);
    } catch (cause) {
      if (!active.current) return;
      if (cause instanceof ApiError && cause.status && cause.status >= 400 && cause.status < 500)
        tracking.forget();
      setError(sourceMutationError(cause, errorKind));
    } finally {
      submitting.current = false;
    }
  }

  /** Applies an edit to the proposal; an edit after a settled attempt starts a new request. */
  function edit(change: () => void) {
    if (tracking.terminal) tracking.forget();
    change();
    setError(null);
  }

  return {
    tracking,
    createdSourceId,
    error,
    setError,
    pendingValidation,
    /** While a submitted proposal is unsettled or unknown it cannot change. */
    frozen:
      pendingValidation || tracking.uncertain || tracking.recovering || tracking.recoveryError,
    submit,
    edit,
    /** Forgets a request the server never accepted, so the person can start again. */
    discard() {
      tracking.forget();
      setError(null);
    },
  };
}

/** The create button's words: open what exists, retry what may exist, or create. */
export function sourceCreationLabel(creation: {
  createdSourceId: string | null;
  tracking: { uncertain: boolean };
}) {
  return creation.createdSourceId
    ? "Open created Source"
    : creation.tracking.uncertain
      ? "Retry Create Source"
      : "Create Source";
}
