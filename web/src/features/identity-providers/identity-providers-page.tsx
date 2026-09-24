import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, KeyRound, Pencil, SearchX, Trash2, WifiOff } from "lucide-react";
import { useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Skeleton } from "@/components/ui/skeleton";
import { TextButton } from "@/components/ui/text-button";
import { listIdentityProvidersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { deleteIdentityProvider } from "@/lib/hey-api/sdk.gen";
import type { IdentityProviderResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import { identityProviderMessages } from "./identity-provider-errors";
import { IdentityProviderDialog } from "./identity-provider-dialog";

export function IdentityProvidersPage() {
  const ui = useAppTranslation();
  const notify = useActionNotifications();
  const queryClient = useQueryClient();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const dialogFocusRef = useRef<HTMLElement | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editProvider, setEditProvider] = useState<IdentityProviderResponse | null>(null);
  const [copiedAlias, setCopiedAlias] = useState<string | null>(null);

  const providers = useQuery({ ...listIdentityProvidersOptions(), retry: false });

  async function refresh() {
    headingRef.current?.focus();
    await queryClient.invalidateQueries({ queryKey: listIdentityProvidersOptions().queryKey });
  }

  function openDialog(target: IdentityProviderResponse | null, trigger: HTMLElement) {
    dialogFocusRef.current = trigger;
    setEditProvider(target);
    setDialogOpen(true);
  }

  async function copyRedirectUri(provider: IdentityProviderResponse) {
    try {
      await navigator.clipboard.writeText(provider.brokerRedirectUri);
      setCopiedAlias(provider.alias);
      window.setTimeout(
        () => setCopiedAlias((current) => (current === provider.alias ? null : current)),
        2000,
      );
    } catch {
      notify({ title: ui("Could not copy the redirect URI."), tone: "error" });
    }
  }

  async function removeProvider(provider: IdentityProviderResponse) {
    await deleteIdentityProvider({
      path: { alias: provider.alias },
    });
    notify({
      title: ui("{{v1}} was removed.", { v1: provider.displayName }),
      tone: "success",
    });
    await refresh();
  }

  const items = providers.data ?? [];

  return (
    <section className="min-h-full px-5 py-12 sm:px-8">
      <div className="mx-auto w-full max-w-[840px]">
        <header className="flex items-end justify-between gap-4 border-b border-border-subtle pb-6">
          <div>
            <KeyRound className="size-8 text-content-secondary" aria-hidden="true" />
            <h1
              ref={headingRef}
              tabIndex={-1}
              className="mt-2 text-2xl font-semibold leading-8 text-content-primary outline-none"
            >
              {ui("Sign-in providers")}
            </h1>
          </div>
          <Button
            size="sm"
            className="shrink-0"
            onClick={(event) => openDialog(null, event.currentTarget)}
          >
            {ui("Add SSO")}
          </Button>
        </header>

        <div className="mt-8" aria-busy={providers.isFetching}>
          {providers.isPending ? (
            <div className="flex flex-col gap-3">
              <Skeleton className="h-24 w-full rounded-2xl" />
              <Skeleton className="h-24 w-full rounded-2xl" />
            </div>
          ) : providers.isError ? (
            <div className="flex flex-col items-center gap-3 rounded-2xl border border-border-subtle px-6 py-12 text-center">
              <WifiOff className="size-8 text-content-muted" aria-hidden="true" />
              <p className="font-main-ui-body text-content-secondary">
                {ui("Could not load identity providers.")}
              </p>
              <TextButton onClick={() => void providers.refetch()}>{ui("Retry")}</TextButton>
            </div>
          ) : items.length === 0 ? (
            <div className="flex flex-col items-center gap-3 rounded-2xl border border-border-subtle px-6 py-12 text-center">
              <SearchX className="size-8 text-content-muted" aria-hidden="true" />
              <p className="font-main-ui-body text-content-secondary">
                {ui("No identity providers yet.")}
              </p>
              <p className="max-w-md font-secondary-body text-content-muted">
                {ui("Add an upstream OIDC provider to let its members sign in through MemoryOS.")}
              </p>
            </div>
          ) : (
            <ul className="flex flex-col gap-3">
              {items.map((provider) => (
                <li key={provider.alias}>
                  <Card className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
                    <div className="min-w-0 flex-1">
                      <span className="font-main-ui-body font-medium text-content-primary">
                        {provider.displayName}
                      </span>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <IconButton
                        size="sm"
                        aria-label={ui("Copy redirect URI")}
                        title={ui("Copy redirect URI")}
                        onClick={() => void copyRedirectUri(provider)}
                      >
                        {copiedAlias === provider.alias ? <Check /> : <Copy />}
                      </IconButton>
                      <IconButton
                        size="sm"
                        aria-label={ui("Edit {{v1}}", { v1: provider.displayName })}
                        title={ui("Edit")}
                        onClick={(event) => openDialog(provider, event.currentTarget)}
                      >
                        <Pencil />
                      </IconButton>
                      <ConfirmDialog
                        title={ui("Remove {{v1}}?", { v1: provider.displayName })}
                        description={ui(
                          "Members can no longer sign in through this provider. Existing accounts and sessions are not deleted.",
                        )}
                        confirmLabel={ui("Remove provider")}
                        pendingLabel={ui("Removing…")}
                        onConfirm={() => removeProvider(provider)}
                        errorMessage={(error) =>
                          presentProblem(error, "mutation", identityProviderMessages).message
                        }
                        trigger={
                          <IconButton
                            size="sm"
                            tone="danger"
                            aria-label={ui("Remove {{v1}}", { v1: provider.displayName })}
                          >
                            <Trash2 />
                          </IconButton>
                        }
                      />
                    </div>
                  </Card>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {dialogOpen ? (
        <IdentityProviderDialog
          open
          provider={editProvider}
          returnFocusRef={dialogFocusRef}
          fallbackFocusRef={headingRef}
          onOpenChange={setDialogOpen}
          onSaved={() => void refresh()}
        />
      ) : null}
    </section>
  );
}
