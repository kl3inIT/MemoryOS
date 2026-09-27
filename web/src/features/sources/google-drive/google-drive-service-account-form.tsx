import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { TriangleAlert } from "lucide-react";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useLayoutEffect, useRef, useState } from "react";
import { useAppForm } from "@/components/form/app-form";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  createGoogleDriveServiceAccount,
  replaceGoogleDriveServiceAccount,
} from "@/lib/hey-api/sdk.gen";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api/types.gen";
import { zGoogleDriveServiceAccountRequest } from "@/lib/hey-api/zod.gen";
import {
  GoogleDriveServiceAccountInput,
  type GoogleDriveServiceAccountInputHandle,
} from "./google-drive-service-account-input";
import { sourceMutationError } from "@/features/sources/shared/source-errors";

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
  const [keyReady, setKeyReady] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);
  const form = useAppForm({
    defaultValues: { name: replacing?.name ?? "", adminEmail: replacing?.accountEmail ?? "" },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: zGoogleDriveServiceAccountRequest.pick({ name: true, adminEmail: true }),
    },
    onSubmit: ({ value }) => save(value),
  });
  const pending = useStore(form.store, (state) => state.isSubmitting);
  // Save waits for a name, an admin email and a readable key, and names what is missing by staying disabled.
  const complete = useStore(form.store, (state) =>
    Boolean(state.values.name.trim() && state.values.adminEmail.trim()),
  );

  useLayoutEffect(
    () => () => {
      controller.current?.abort();
    },
    [],
  );

  async function save(value: { name: string; adminEmail: string }) {
    if (disabled || !keyReady || !value.name.trim() || !value.adminEmail.trim()) return;
    const request = new AbortController();
    controller.current = request;
    setError(null);
    onPendingChange(true);
    try {
      // A direct call, not a mutation: the private key must never enter React Query variables or caches.
      const body = {
        name: value.name.trim(),
        serviceAccountKeyJson: keyInput.current?.takeJson() ?? "",
        adminEmail: value.adminEmail.trim(),
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
        onPendingChange(false);
      }
    }
  }

  const locked = disabled || pending;
  return (
    <form
      className="flex flex-col gap-5"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <form.AppField name="name">
        {(field) => (
          <field.TextField
            label={ui("Credential name")}
            maxLength={120}
            disabled={locked}
            placeholder={ui("e.g. Company Google Workspace")}
            autoComplete="off"
          />
        )}
      </form.AppField>
      <form.AppField name="adminEmail">
        {(field) => (
          <field.TextField
            label={ui("Primary admin email")}
            type="email"
            maxLength={320}
            disabled={locked}
            placeholder={ui("admin@company.com")}
            autoComplete="off"
          />
        )}
      </form.AppField>
      {replacing ? (
        <Alert variant="warning" role="note">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>
            {ui(
              "Replacing the key affects all {{v1}} Sources using this credential. Use a key of the same service account; saved links and indexed documents are retained.",
              { v1: replacing.sourceCount },
            )}
          </AlertDescription>
        </Alert>
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
      <Button type="submit" pending={pending} disabled={locked || !keyReady || !complete}>
        {replacing ? ui("Replace key") : ui("Save service account")}
      </Button>
    </form>
  );
}
