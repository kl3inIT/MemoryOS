import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, KeyRound, Pencil, SearchX, Trash2, WifiOff } from "lucide-react";
import { useRef, useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  deleteIdentityProviderMutation,
  listIdentityProvidersOptions,
  listIdentityProvidersQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
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
  const remove = useMutation(deleteIdentityProviderMutation());

  async function refresh() {
    headingRef.current?.focus();
    await queryClient.invalidateQueries({ queryKey: listIdentityProvidersQueryKey() });
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
    await remove.mutateAsync({ path: { alias: provider.alias } });
    notify({
      title: ui("{{v1}} was removed.", { v1: provider.displayName }),
      tone: "success",
    });
    await refresh();
  }

  const items = providers.data ?? [];

  return (
    <SettingsLayout>
      <PageHeader
        title={ui("Sign-in providers")}
        titleRef={headingRef}
        icon={<KeyRound />}
        iconSize="lg"
        actions={
          <Button size="sm" onClick={(event) => openDialog(null, event.currentTarget)}>
            {ui("Add SSO")}
          </Button>
        }
      />

      <div aria-busy={providers.isFetching}>
        {providers.isPending ? (
          <div className="flex flex-col gap-3">
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : providers.isError ? (
          <EmptyState
            role="alert"
            icon={<WifiOff />}
            title={ui("Could not load identity providers.")}
            action={
              <Button size="sm" prominence="secondary" onClick={() => void providers.refetch()}>
                {ui("Retry")}
              </Button>
            }
          />
        ) : items.length === 0 ? (
          <EmptyState
            icon={<SearchX />}
            title={ui("No identity providers yet.")}
            detail={ui(
              "Add an upstream OIDC provider to let its members sign in through MemoryOS.",
            )}
          />
        ) : (
          <ul className="flex flex-col gap-3">
            {items.map((provider) => (
              <li key={provider.alias}>
                <Card size="sm">
                  <CardContent>
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
                      <span className="min-w-0 flex-1 font-main-ui-action text-content-primary">
                        {provider.displayName}
                      </span>
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
                    </div>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ul>
        )}
      </div>

      {dialogOpen ? (
        <IdentityProviderDialog
          provider={editProvider}
          returnFocusRef={dialogFocusRef}
          fallbackFocusRef={headingRef}
          onClose={() => setDialogOpen(false)}
          onSaved={() => void refresh()}
        />
      ) : null}
    </SettingsLayout>
  );
}
