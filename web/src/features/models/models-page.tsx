import { Boxes } from "lucide-react";
import { useLayoutEffect, useState } from "react";
import { flushSync } from "react-dom";
import { SectionHeader } from "@/components/composites/section-header";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { AddProviderSection } from "./add-provider-section";
import { TaskModels, TenantDefault } from "./model-defaults";
import { ModelDiscovery } from "./model-discovery";
import { ModelEditor } from "./model-editor";
import { ProviderConnectionCard } from "./provider-connection-card";
import { ProviderEditor } from "./provider-editor";
import { useModelsAdministration, type Deletion } from "./use-models-administration";

/**
 * Denial never mounts catalog queries; authority changes also retire every draft and direct call. Leaving the page
 * unmounts the administration synchronously, so every draft and key is dropped and every catalog mutation in flight is
 * aborted before the page can enter the back/forward cache; returning mounts a fresh one.
 */
export function ModelsPage() {
  const session = useApplicationSession();
  const [active, setActive] = useState(true);
  useLayoutEffect(() => {
    const hide = () => flushSync(() => setActive(false));
    const show = () => setActive(true);
    window.addEventListener("pagehide", hide);
    window.addEventListener("pageshow", show);
    return () => {
      window.removeEventListener("pagehide", hide);
      window.removeEventListener("pageshow", show);
    };
  }, []);
  if (!session.capabilities.includes("MODELS_MANAGE")) return <AccessDeniedScreen />;
  if (!active) return null;
  return (
    <ModelsAdministration
      key={JSON.stringify([
        session.actorId,
        session.authorizationVersion,
        session.capabilities,
        session.scopedCapabilities,
      ])}
    />
  );
}

function ModelsAdministration() {
  const ui = useAppTranslation();
  const page = useModelsAdministration();
  const { providers, adapters, models, catalogPending, catalogError, unavailable, setEditor } =
    page;
  const hasProviders = Boolean(providers.data?.length);

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Models")}
        icon={<Boxes />}
        actions={
          <Button
            prominence="secondary"
            pending={page.reloading.pending}
            disabled={page.busy}
            onClick={() => void page.reload()}
          >
            {ui("Refresh catalog")}
          </Button>
        }
      />

      {/* Models by task — the Onyx default card plus its per-flow defaults */}
      {hasProviders && !catalogError && (
        <section aria-labelledby="task-models" className="flex flex-col gap-3">
          <SectionHeader id="task-models" title={ui("Models by task")} />
          <Card>
            <CardContent>
              {catalogPending ? (
                <p role="status">{ui("Loading model catalog…")}</p>
              ) : (
                <div className="flex flex-col divide-y divide-border-subtle *:py-4 *:first:pt-0 *:last:pb-0">
                  <TenantDefault
                    providers={providers.data!}
                    models={models}
                    adapters={adapters.data!}
                  />
                  <TaskModels
                    providers={providers.data!}
                    models={models}
                    adapters={adapters.data!}
                  />
                </div>
              )}
            </CardContent>
          </Card>
        </section>
      )}

      {catalogPending && <p role="status">{ui("Loading model catalog…")}</p>}
      {catalogError && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>
            {ui(
              "The complete catalog could not be loaded. Displayed records may be stale. Refresh before changing configurations or defaults.",
            )}
          </AlertDescription>
        </Alert>
      )}
      {page.reloading.error && !page.deletion && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{ui(page.reloading.error)}</AlertDescription>
        </Alert>
      )}

      {/* Available connections — Onyx existing-provider cards */}
      {hasProviders && (
        <section aria-labelledby="available-connections" className="flex flex-col gap-3">
          <SectionHeader id="available-connections" title={ui("Available connections")} />
          <div className="flex flex-col gap-2">
            {page.sortedProviders.map((provider) => (
              <ProviderConnectionCard
                key={provider.id}
                provider={provider}
                models={models.filter((model) => model.providerId === provider.id)}
                adapters={adapters.data ?? []}
                isDefault={page.isDefaultProvider(provider)}
                defaultModelId={page.defaultModelId}
                unavailable={unavailable}
                actions={{
                  onEdit: () => setEditor({ kind: "provider", initial: provider }),
                  onDiscover: () => setEditor({ kind: "discovery", providerId: provider.id }),
                  onAddModel: () => setEditor({ kind: "model", providerId: provider.id }),
                  onEditModel: (model) =>
                    setEditor({ kind: "model", providerId: provider.id, initial: model }),
                  onDelete: () => page.startDeletion({ kind: "provider", provider }),
                  onDeleteModel: (model) => page.startDeletion({ kind: "model", model }),
                }}
              />
            ))}
          </div>
        </section>
      )}

      {/* Add connection — Onyx provider grid */}
      {adapters.data?.some((adapter) => adapter.type === "openai") && (
        <AddProviderSection
          disabled={unavailable || page.atProviderLimit}
          onConnect={(preset) => setEditor({ kind: "provider", preset })}
        />
      )}
      {!catalogPending && !adapters.data?.length && !catalogError && (
        <p role="status">{ui("No provider adapters are installed.")}</p>
      )}

      <ModelsEditors page={page} />
      {page.deletion && (
        <DeletionDialog
          deletion={page.deletion}
          onCancel={page.cancelDeletion}
          onConfirm={page.remove}
        />
      )}
    </SettingsLayout>
  );
}

/** The one editor the page has open: a provider, a model, or the provider's model listing. */
function ModelsEditors({ page }: { page: ReturnType<typeof useModelsAdministration> }) {
  const { editor, editedProvider, models, providers, adapters, setEditor } = page;
  const close = () => setEditor(null);
  if (editor?.kind === "provider" && adapters.data)
    return (
      <ProviderEditor
        initial={editor.initial}
        models={models.filter((model) => model.providerId === editor.initial?.id)}
        providers={providers.data ?? []}
        adapters={adapters.data}
        preferredAdapterType={editor.initial ? undefined : "openai"}
        preferredBaseUrl={editor.preset?.baseUrl}
        preferredName={editor.preset?.name}
        onClose={close}
      />
    );
  if (!editedProvider) return null;
  const adapter = adapters.data?.find((entry) => entry.type === editedProvider.adapterType);
  const providerModels = models.filter((model) => model.providerId === editedProvider.id);
  if (editor?.kind === "discovery")
    return adapter ? (
      <ModelDiscovery
        provider={editedProvider}
        adapter={adapter}
        models={providerModels}
        onClose={close}
      />
    ) : null;
  if (editor?.kind === "model")
    return (
      <ModelEditor
        key={editor.modelName ?? editor.initial?.id ?? "new"}
        initial={editor.initial}
        modelName={editor.modelName}
        models={models}
        provider={editedProvider}
        adapter={adapter}
        onClose={close}
      />
    );
  return null;
}

function DeletionDialog({
  deletion,
  onCancel,
  onConfirm,
}: {
  deletion: Deletion;
  onCancel: () => void;
  onConfirm: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  return (
    <ConfirmDialog
      open
      onOpenChange={(open) => {
        if (!open) onCancel();
      }}
      title={
        deletion.kind === "provider"
          ? ui(appText("Delete provider {{name}}?", { name: deletion.provider.name }))
          : ui(appText("Delete model {{name}}?", { name: deletion.model.displayName }))
      }
      description={
        deletion.kind === "provider"
          ? ui(
              "Every configured model on this provider is removed. Affected Persona defaults are cleared, task models fall back to the conversation model and Chat history is kept. Replace a Tenant default first.",
            )
          : ui(
              "Affected Persona defaults are cleared, task models fall back to the conversation model and Chat history is kept. Replace a Tenant default first.",
            )
      }
      confirmLabel={ui("Delete configuration")}
      pendingLabel={ui("Deleting configuration")}
      onConfirm={onConfirm}
      errorMessage={(error) =>
        error instanceof Error ? error.message : ui("Deletion failed. Refresh before retrying.")
      }
    />
  );
}
