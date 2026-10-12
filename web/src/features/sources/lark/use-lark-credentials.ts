import { appText, type AppCopy } from "@/i18n/app-text";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import {
  deleteLarkCredentialMutation,
  listLarkCredentialsOptions,
  renameLarkCredentialMutation,
  testLarkCredentialMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { LarkCredentialResponse } from "@/lib/hey-api/types.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";

type CredentialDialog = {
  open: boolean;
  /** Counts openings, so every opening starts from an empty form. */
  session: number;
  replacing: LarkCredentialResponse | null;
};

/**
 * The Lark credentials of the Tenant and what the setup does with them: test, rename, delete,
 * and the dialog that registers an app or replaces its App Secret.
 */
export function useLarkCredentials({
  onSelect,
  onBusyChange,
}: {
  onSelect: (credentialId: string) => void;
  onBusyChange?: (busy: boolean) => void;
}) {
  const notify = useActionNotifications();
  const credentials = useQuery({ ...listLarkCredentialsOptions(), retry: false });
  const remove = useMutation(deleteLarkCredentialMutation());
  const rename = useMutation(renameLarkCredentialMutation());
  const test = useMutation(testLarkCredentialMutation());
  const dialogTrigger = useRef<HTMLButtonElement | null>(null);
  const active = useRef(true);
  const [dialog, setDialog] = useState<CredentialDialog>({
    open: false,
    session: 0,
    replacing: null,
  });
  const [saving, setSaving] = useState(false);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [managedId, setManagedId] = useState<string | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const [testedId, setTestedId] = useState<string | null>(null);
  const busy = saving || remove.isPending || rename.isPending || test.isPending;

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);

  useLayoutEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);

  function openDialog(trigger: HTMLButtonElement, replacing: LarkCredentialResponse | null) {
    if (busy) return;
    dialogTrigger.current = trigger;
    setError(null);
    setDialog((current) => ({ open: true, session: current.session + 1, replacing }));
  }

  async function runTest(credential: LarkCredentialResponse) {
    if (busy || !credential.actions.includes("test")) return;
    setError(null);
    setTestedId(null);
    try {
      const result = await test.mutateAsync({ path: { credentialId: credential.id } });
      if (!active.current) return;
      setTestedId(credential.id);
      notify({
        title: "Credential works",
        description:
          result.missingScopes.length === 0
            ? appText("{{v1}} signs in as {{v2}} and has every scope it needs.", {
                v1: credential.name,
                v2: result.botName,
              })
            : appText("{{v1}} signs in as {{v2}}, but {{v3}} scopes are missing.", {
                v1: credential.name,
                v2: result.botName,
                v3: result.missingScopes.length,
              }),
        tone: result.missingScopes.length === 0 ? "success" : "info",
      });
    } catch (cause) {
      if (!active.current) return;
      setError(sourceMutationError(cause, "lark-credential"));
    } finally {
      await credentials.refetch();
    }
  }

  async function saveName(credential: LarkCredentialResponse, name: string) {
    await rename.mutateAsync({
      path: { credentialId: credential.id },
      headers: { "If-Match": `"${credential.credentialRevision}"` },
      body: { name: name.trim() },
    });
    if (!active.current) return;
    setRenaming(null);
    await credentials.refetch();
  }

  async function deleteCredential(credential: LarkCredentialResponse) {
    if (!credential.actions.includes("delete") || credential.sourceCount !== 0)
      throw new Error("Credential is unavailable");
    await remove.mutateAsync({
      path: { credentialId: credential.id },
      headers: { "If-Match": `"${credential.credentialRevision}"` },
    });
    if (!active.current) return;
    notify({
      title: "Credential deleted",
      description: appText("{{v1}} and its stored App Secret were deleted.", {
        v1: credential.name,
      }),
      tone: "success",
    });
    await credentials.refetch();
  }

  return {
    credentials,
    dialog,
    dialogTrigger,
    busy,
    renamePending: rename.isPending,
    testPending: test.isPending,
    unavailable: credentials.isPending || credentials.isError,
    error,
    setError,
    testedId,
    renaming,
    setRenaming,
    managedId,
    toggleManaged: (credentialId: string) =>
      setManagedId((current) => (current === credentialId ? null : credentialId)),
    setSaving,
    openDialog,
    /** The dialog has no trigger of its own, so it only ever asks to close. */
    changeDialog(open: boolean) {
      if (busy || open) return;
      setError(null);
      setDialog((current) => ({ ...current, open: false, replacing: null }));
    },
    async credentialSaved(credential: LarkCredentialResponse) {
      setDialog((current) => ({ ...current, open: false, replacing: null }));
      onSelect(credential.id);
      await credentials.refetch();
    },
    runTest,
    saveName,
    deleteCredential,
  };
}

export type LarkCredentials = ReturnType<typeof useLarkCredentials>;
