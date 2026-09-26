import { useQuery } from "@tanstack/react-query";
import { History, Plus, RefreshCw, ScanSearch, Server } from "lucide-react";
import { useLayoutEffect, useState } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/ui/status-badge";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { appText } from "@/i18n/app-text";
import { formatUiDate } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ApiError } from "@/lib/api";
import {
  getSearchSettingsOptions,
  listEmbeddingModelPresetsOptions,
  listEmbeddingProvidersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { EmbeddingProviderResponse } from "@/lib/hey-api/types.gen";
import { ChangeModelDialog } from "./change-model-dialog";
import { EmbeddingProviderEditor } from "./embedding-provider-editor";
import {
  rebuildPollMillis,
  retained,
  searchActions,
  useCount,
  type SearchSettings,
} from "./search-settings";
import { SearchSettingsConfirm, type Confirmation } from "./search-settings-confirm";
import { GenerationIdentity, ProviderRow, RebuildSection } from "./search-settings-parts";

/** `/admin/search-settings`: denial never mounts a query; authority changes retire every draft. */
export function SearchSettingsPage() {
  const session = useApplicationSession();
  const [active, setActive] = useState(true);
  useLayoutEffect(() => {
    const hide = () => setActive(false);
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
    <SearchSettingsAdministration
      key={JSON.stringify([session.actorId, session.authorizationVersion, session.capabilities])}
    />
  );
}

type Editor = { kind: "provider"; initial?: EmbeddingProviderResponse } | { kind: "model" };

function SearchSettingsAdministration() {
  const ui = useAppTranslation();
  const settings = useQuery({
    ...getSearchSettingsOptions(),
    retry: false,
    // The rebuild advances on the worker, so its progress is reread only while there is one.
    refetchInterval: (query) => (query.state.data?.future ? rebuildPollMillis : false),
  });
  const providers = useQuery({ ...listEmbeddingProvidersOptions(), retry: false });
  const presets = useQuery({ ...listEmbeddingModelPresetsOptions(), retry: false });
  const [editor, setEditor] = useState<Editor | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);

  const forbidden = [settings.error, providers.error].some(
    (error) => error instanceof ApiError && error.status === 403,
  );
  const failed = settings.isError || providers.isError;

  return (
    <SettingsLayout wide>
      <PageHeader
        title={ui("Cấu hình tìm kiếm")}
        icon={<ScanSearch />}
        description={ui("Model embedding dùng để index tài liệu.")}
      />
      {forbidden ? (
        <EmptyState
          role="alert"
          icon={<ScanSearch />}
          title={ui("Không có quyền")}
          detail={ui("Chỉ quản trị model của Tenant vận hành được đổi cấu hình tìm kiếm.")}
        />
      ) : failed ? (
        <EmptyState
          role="alert"
          icon={<ScanSearch />}
          title={ui("Không tải được cấu hình tìm kiếm.")}
          action={
            <Button
              prominence="secondary"
              onClick={() => {
                void settings.refetch();
                void providers.refetch();
              }}
            >
              <RefreshCw aria-hidden="true" />
              {ui("Tải lại")}
            </Button>
          }
        />
      ) : settings.isPending || providers.isPending ? (
        <div role="status" className="flex flex-col gap-8">
          <span className="sr-only">{ui("Đang tải…")}</span>
          {[0, 1, 2].map((index) => (
            <div key={index} className="flex flex-col gap-3">
              <Skeleton className="h-6 w-48" />
              <Skeleton className="h-24 w-full" />
            </div>
          ))}
        </div>
      ) : (
        <Loaded
          settings={settings.data}
          providers={providers.data}
          presetsLoading={presets.isPending}
          onChangeModel={() => setEditor({ kind: "model" })}
          onAddProvider={() => setEditor({ kind: "provider" })}
          onEditProvider={(provider) => setEditor({ kind: "provider", initial: provider })}
          onConfirm={setConfirmation}
        />
      )}

      {editor?.kind === "model" && settings.data && providers.data && (
        <ChangeModelDialog
          present={settings.data.present}
          providers={providers.data}
          presets={presets.data ?? []}
          onClose={() => setEditor(null)}
        />
      )}
      {editor?.kind === "provider" && settings.data && (
        <EmbeddingProviderEditor
          initial={editor.initial}
          testModel={testModelFor(editor.initial, settings.data)}
          onClose={() => setEditor(null)}
        />
      )}
      {confirmation && (
        <SearchSettingsConfirm confirmation={confirmation} onClose={() => setConfirmation(null)} />
      )}
    </SettingsLayout>
  );
}

/** A connection check asks for the model the provider serves now, or the present one for a new provider. */
function testModelFor(provider: EmbeddingProviderResponse | undefined, settings: SearchSettings) {
  const serving = [settings.future, settings.present].find(
    (generation) => generation && generation.providerId === provider?.id,
  );
  const source = serving ?? settings.present;
  return { model: source.model, dimensions: source.dimensions };
}

function Loaded({
  settings,
  providers,
  presetsLoading,
  onChangeModel,
  onAddProvider,
  onEditProvider,
  onConfirm,
}: {
  settings: SearchSettings;
  providers: EmbeddingProviderResponse[];
  presetsLoading: boolean;
  onChangeModel: () => void;
  onAddProvider: () => void;
  onEditProvider: (provider: EmbeddingProviderResponse) => void;
  onConfirm: (confirmation: Confirmation) => void;
}) {
  const ui = useAppTranslation();
  const count = useCount();
  const actions = searchActions(settings);
  const present = settings.present;
  return (
    <>
      <section aria-labelledby="search-present" className="flex flex-col gap-3">
        <SectionHeader
          id="search-present"
          title={ui("Đang dùng")}
          actions={
            <Button
              size="sm"
              disabled={!actions.changeModel || presetsLoading || providers.length === 0}
              onClick={onChangeModel}
            >
              {ui("Đổi model")}
            </Button>
          }
        />
        <GenerationIdentity generation={present} />
        <StatStrip columns={3}>
          <StatTile label={ui("Số chiều")} value={count(present.dimensions)} />
          <StatTile label={ui("Tài liệu")} value={count(present.documentCount)} />
          <StatTile
            label={ui("Kích hoạt")}
            value={
              present.activatedAt
                ? formatUiDate(present.activatedAt, {
                    day: "numeric",
                    month: "numeric",
                    year: "numeric",
                  })
                : "—"
            }
            hint={
              present.activatedAt
                ? formatUiDate(present.activatedAt, { timeStyle: "short" })
                : undefined
            }
          />
        </StatStrip>
      </section>

      {settings.future && (
        <RebuildSection
          present={present}
          future={settings.future}
          progress={settings.rebuild}
          showSwitch={actions.showSwitch}
          switchEnabled={actions.switchEnabled}
          onSwitch={() => onConfirm({ kind: "switch", future: settings.future! })}
          onCancel={() => onConfirm({ kind: "cancel", future: settings.future! })}
        />
      )}

      <section aria-labelledby="search-providers" className="flex flex-col gap-3">
        <SectionHeader
          id="search-providers"
          title={ui("Provider embedding")}
          actions={
            <Button size="sm" prominence="secondary" onClick={onAddProvider}>
              <Plus aria-hidden="true" />
              {ui("Thêm provider")}
            </Button>
          }
        />
        {providers.length === 0 ? (
          <Card>
            <EmptyState icon={<Server />} title={ui("Chưa có provider embedding.")} />
          </Card>
        ) : (
          <ul className="flex flex-col gap-2">
            {providers.map((provider) => (
              <ProviderRow
                key={provider.id}
                provider={provider}
                onEdit={() => onEditProvider(provider)}
                onDelete={() => onConfirm({ kind: "deleteProvider", provider })}
              />
            ))}
          </ul>
        )}
      </section>

      <section aria-labelledby="search-past" className="flex flex-col gap-3">
        <SectionHeader id="search-past" title={ui("Index cũ")} />
        {settings.past.length === 0 ? (
          <p className="font-secondary-body text-content-muted">{ui("Không có index cũ.")}</p>
        ) : (
          <SettingRows>
            {settings.past.map((past) => (
              <SettingRow
                key={past.id}
                icon={<History />}
                className="flex-wrap sm:flex-nowrap"
                title={
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="break-all">{past.model}</span>
                    {past.cleanupBlocked && (
                      <StatusBadge tone="danger">{ui("Không xoá được index")}</StatusBadge>
                    )}
                  </span>
                }
                description={
                  <span className="flex flex-col gap-0.5">
                    <span className="break-words tabular-nums">
                      {ui(
                        appText("{{provider}} · {{count}} tài liệu", {
                          provider: past.providerName,
                          count: count(past.documentCount),
                        }),
                      )}
                    </span>
                    <span className="tabular-nums">
                      {past.retainedUntil && retained(past)
                        ? ui(
                            appText("Giữ đến {{date}}", {
                              date: formatUiDate(past.retainedUntil, {
                                dateStyle: "medium",
                                timeStyle: "short",
                              }),
                            }),
                          )
                        : ui("Hết hạn giữ")}
                    </span>
                  </span>
                }
                control={
                  <Button
                    size="sm"
                    prominence="secondary"
                    disabled={!actions.restore(past)}
                    aria-label={ui(appText("Hoàn tác về {{model}}", { model: past.model }))}
                    onClick={() => onConfirm({ kind: "restore", past })}
                  >
                    {ui("Hoàn tác")}
                  </Button>
                }
              />
            ))}
          </SettingRows>
        )}
      </section>
    </>
  );
}
