import type { AppCopy } from "@/i18n/app-text";
import { statusLabel } from "@/i18n/status-copy";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import type { SourceOperation } from "@/lib/hey-api/types.gen";

/** What a Source creation in flight tells the page about its request. */
type CreationState = {
  tracking: {
    operation: SourceOperation | null | undefined;
    recovering: boolean;
    recoveryError: boolean;
    recoveryMissing: boolean;
    statusUnavailable: boolean;
    uncertain: boolean;
    retryRecovery: () => unknown;
    retryStatus: () => unknown;
  };
  createdSourceId: string | null;
  error: AppCopy | null;
  pendingValidation: boolean;
  discard: () => void;
};

/**
 * The state of a submitted Source proposal: its verification, recovery of a request whose answer
 * was lost, and the ways back when its status cannot be read.
 */
export function SourceCreationStatus({
  creation,
  busy,
  pendingMessage,
}: {
  creation: CreationState;
  busy: boolean;
  /** What the provider is verifying while the proposal waits. */
  pendingMessage: string;
}) {
  const ui = useAppTranslation();
  const { tracking, createdSourceId, error, pendingValidation } = creation;

  return (
    <>
      {tracking.operation && !createdSourceId ? (
        <Alert variant={pendingValidation ? "info" : "warning"} role="status">
          <AlertDescription>
            <div className="flex flex-col items-start gap-2">
              <StatusBadge tone={pendingValidation ? "info" : "warning"}>
                {pendingValidation ? ui("Pending validation") : ui("Proposal not activated")}
              </StatusBadge>
              <p>
                {pendingValidation
                  ? pendingMessage
                  : ui("Review the error and edit the proposal before submitting again.")}
              </p>
              <p className="break-all text-xs text-content-muted">
                {ui("Operation")} {tracking.operation.id} ·{" "}
                {ui(statusLabel(tracking.operation.status))}
              </p>
            </div>
          </AlertDescription>
        </Alert>
      ) : null}
      {tracking.recovering ? <p role="status">{ui("Recovering your submitted Source…")}</p> : null}
      {!error && (tracking.recoveryError || tracking.statusUnavailable) ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui("Validation status is unavailable. This does not mean creation failed.")}
        </p>
      ) : null}
      {tracking.recoveryError ? (
        <Button prominence="secondary" onClick={() => void tracking.retryRecovery()}>
          {ui("Recover submitted Source")}
        </Button>
      ) : null}
      {tracking.recoveryMissing ? (
        <Button prominence="secondary" onClick={creation.discard}>
          {ui("Discard unaccepted request and start again")}
        </Button>
      ) : null}
      {tracking.statusUnavailable ? (
        <Button prominence="secondary" onClick={() => void tracking.retryStatus()}>
          {ui("Retry validation status")}
        </Button>
      ) : null}
      {tracking.uncertain && !busy ? (
        <p className="text-sm text-content-muted">
          {ui(
            "No receipt was received. Retry this unchanged proposal with the same request ID to avoid duplicate Sources.",
          )}
        </p>
      ) : null}
    </>
  );
}
