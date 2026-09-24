import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { TriangleAlert } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  createGoogleDriveServiceAccount,
  replaceGoogleDriveServiceAccount,
} from "@/lib/hey-api/sdk.gen";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api/types.gen";
import {
  GoogleDriveServiceAccountInput,
  type GoogleDriveServiceAccountInputHandle,
} from "./google-drive-service-account-input";
import { sourceMutationError } from "./source-errors";

type GoogleDriveServiceAccountFormProps = {
  /** The service-account credential whose key is replaced; absent when creating one. */
  replacing: GoogleDriveCredentialResponse | null;
  disabled: boolean;
  onPendingChange: (pending: boolean) => void;
  onSaved: (credential: GoogleDriveCredentialResponse) => void;
  onFailed: (cause: unknown) => void;
};

/**
 * Stores a domain-wide-delegated service account. MemoryOS verifies the key against Google as the
 * primary admin before saving, so a rejected delegation or a non-admin email stores nothing.
 */
export function GoogleDriveServiceAccountForm({
  replacing,
  disabled,
  onPendingChange,
  onSaved,
  onFailed,
}: GoogleDriveServiceAccountFormProps) {
  const ui = useAppTranslation();
  const keyInput = useRef<GoogleDriveServiceAccountInputHandle>(null);
  const controller = useRef<AbortController | null>(null);
  const [name, setName] = useState(replacing?.name ?? "");
  const [adminEmail, setAdminEmail] = useState(replacing?.accountEmail ?? "");
  const [keyReady, setKeyReady] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);

  useLayoutEffect(
    () => () => {
      controller.current?.abort();
    },
    [],
  );

  async function save() {
    if (pending || disabled || !keyReady || !name.trim() || !adminEmail.trim()) return;
    const request = new AbortController();
    controller.current = request;
    setError(null);
    setPending(true);
    onPendingChange(true);
    try {
      // The private key must never enter React Query variables or caches.
      const body = {
        name: name.trim(),
        serviceAccountKeyJson: keyInput.current?.takeJson() ?? "",
        adminEmail: adminEmail.trim(),
      };
      const { data } = replacing
        ? await replaceGoogleDriveServiceAccount({
            path: { credentialId: replacing.id },
            headers: {
              "If-Match": `"${replacing.credentialRevision}"`,
            },
            body,
            signal: request.signal,
          })
        : await createGoogleDriveServiceAccount({
            body,
            signal: request.signal,
          });
      onSaved(data);
    } catch (cause) {
      if (request.signal.aborted) return;
      setError(sourceMutationError(cause, "google-drive"));
      onFailed(cause);
    } finally {
      if (controller.current === request) {
        controller.current = null;
        setPending(false);
        onPendingChange(false);
      }
    }
  }

  const locked = disabled || pending;
  return (
    <form
      className="space-y-5"
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      <div>
        <label
          htmlFor="google-drive-service-account-name"
          className="text-sm font-medium text-content-primary"
        >
          {ui("Credential name")}
        </label>
        <Input
          id="google-drive-service-account-name"
          value={name}
          maxLength={120}
          required
          disabled={locked}
          onChange={(event) => setName(event.target.value)}
          placeholder={ui("e.g. Company Google Workspace")}
          autoComplete="off"
          className="mt-2"
        />
      </div>
      <div>
        <label
          htmlFor="google-drive-service-account-admin"
          className="text-sm font-medium text-content-primary"
        >
          {ui("Primary admin email")}
        </label>
        <Input
          id="google-drive-service-account-admin"
          type="email"
          value={adminEmail}
          maxLength={320}
          required
          disabled={locked}
          onChange={(event) => setAdminEmail(event.target.value)}
          placeholder={ui("admin@company.com")}
          autoComplete="off"
          className="mt-2"
        />
      </div>
      {replacing ? (
        <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
          {ui(
            "Replacing the key affects all {{v1}} Sources using this credential. Use a key of the same service account; saved links and indexed documents are retained.",
            { v1: replacing.sourceCount },
          )}
        </p>
      ) : null}
      <GoogleDriveServiceAccountInput
        ref={keyInput}
        disabled={locked}
        onReadyChange={setKeyReady}
      />
      {error ? (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}
      {pending ? (
        <p role="status" className="text-sm text-content-secondary">
          {ui("Verifying the service account with Google…")}
        </p>
      ) : null}
      <Button
        type="submit"
        pending={pending}
        disabled={locked || !keyReady || !name.trim() || !adminEmail.trim()}
      >
        {replacing ? ui("Replace key") : ui("Save service account")}
      </Button>
    </form>
  );
}
