import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowRight,
  History,
  Plus,
  RefreshCw,
  ScanSearch,
  Server,
  Settings2,
  Trash2,
} from "lucide-react";
import { useLayoutEffect, useState, type ReactNode } from "react";
import { EmptyState } from "@/components/composites/empty-state";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { StatStrip, StatTile } from "@/components/composites/stat-strip";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Progress } from "@/components/ui/progress";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { StatusBadge } from "@/components/ui/status-badge";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { AccessDeniedScreen } from "@/features/identity/session-states";
import { DataBoundaryTag } from "@/features/models/data-boundary";
import { providerTileClassName } from "@/components/provider-logos/provider-card";
import { appText } from "@/i18n/app-text";
import { formatUiDate, uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { ApiError, sameOriginMutationHeaders } from "@/lib/api";
import {
  getSearchSettingsOptions,
  listEmbeddingModelPresetsOptions,
  listEmbeddingProvidersOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  cancelSearchFutureGeneration,
  deleteEmbeddingProvider,
  restoreSearchPastGeneration,
  switchSearchFutureGeneration,
} from "@/lib/hey-api/sdk.gen";
import type { EmbeddingProviderResponse } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { ChangeModelDialog } from "./change-model-dialog";
import { EmbeddingProviderEditor } from "./embedding-provider-editor";
import {
  rebuildPercent,
  rebuildPollMillis,
  refreshSearchSettings,
  remainingTime,
  retained,
  searchActions,
  searchSettingsProblem,
  type Generation,
  type RebuildProgress,
  type SearchSettings,
} from "./search-settings";

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

type Confirmation =
  | { kind: "switch"; future: Generation }
  | { kind: "cancel"; future: Generation }
  | { kind: "restore"; past: Generation }
  | { kind: "deleteProvider"; provider: EmbeddingProviderResponse };
type Editor = { kind: "provider"; initial?: EmbeddingProviderResponse } | { kind: "model" };

function useCount() {
  const format = new Intl.NumberFormat(uiLocale());
  return (value: number) => format.format(value);
}

function SearchSettingsAdministration() {
  const ui = useAppTranslation();
  const client = useQueryClient();
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
        <div role="status" className="space-y-8">
          <span className="sr-only">{ui("Đang tải…")}</span>
          {[0, 1, 2].map((index) => (
            <div key={index} className="space-y-3">
              <Skeleton className="h-6 w-48" />
              <Skeleton className="h-24 w-full rounded-2xl" />
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
        <Confirm
          confirmation={confirmation}
          onClose={() => setConfirmation(null)}
          onDone={() => refreshSearchSettings(client)}
        />
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
      <section aria-labelledby="search-present" className="space-y-3">
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

      <section aria-labelledby="search-providers" className="space-y-3">
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

      <section aria-labelledby="search-past" className="space-y-3">
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

/** Model on the first line, provider and where the data goes on the second. */
function GenerationIdentity({ generation, badge }: { generation: Generation; badge?: ReactNode }) {
  return (
    <div className="flex min-w-0 items-start gap-3">
      <span className={cn(providerTileClassName, "text-content-secondary")}>
        <Server className="size-4" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-center gap-2">
          <span className="break-all font-main-ui-action text-content-primary">
            {generation.model}
          </span>
          {badge}
        </p>
        <p className="flex flex-wrap items-center gap-2 font-secondary-body text-content-muted">
          <span className="break-words">{generation.providerName}</span>
          <DataBoundaryTag boundary={generation.dataBoundary} />
        </p>
      </div>
    </div>
  );
}

function RebuildSection({
  present,
  future,
  progress,
  showSwitch,
  switchEnabled,
  onSwitch,
  onCancel,
}: {
  present: Generation;
  future: Generation;
  progress: RebuildProgress | null;
  showSwitch: boolean;
  switchEnabled: boolean;
  onSwitch: () => void;
  onCancel: () => void;
}) {
  const ui = useAppTranslation();
  const count = useCount();
  const percent = progress ? rebuildPercent(progress) : 0;
  const sameModel = present.model === future.model;
  return (
    <section aria-labelledby="search-rebuild" className="space-y-3">
      <SectionHeader id="search-rebuild" title={ui("Đang dựng lại")} />
      <Card size="sm" className="gap-3">
        {/* The actions wrap under the models on a phone instead of squeezing them. */}
        <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2 px-4">
          <div className="min-w-0 flex-[1_1_16rem]">
            <p className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5">
              {!sameModel && (
                <span className="inline-flex min-w-0 items-center gap-1.5">
                  <span className="break-words font-main-ui-body text-content-muted">
                    {present.model}
                  </span>
                  <ArrowRight className="size-3.5 shrink-0 text-content-muted" aria-hidden="true" />
                  <span className="sr-only">{ui("sang")}</span>
                </span>
              )}
              <span className="min-w-0 break-words font-main-ui-action text-content-primary">
                {future.model}
              </span>
              {future.automatic && <Badge variant="secondary">{ui("Tự động")}</Badge>}
            </p>
            <p className="mt-0.5 flex flex-wrap items-center gap-2 font-secondary-body text-content-muted">
              <span className="break-words">{future.providerName}</span>
              <DataBoundaryTag boundary={future.dataBoundary} />
            </p>
          </div>
          <div className="ml-auto flex shrink-0 items-center gap-1.5">
            <Button size="sm" prominence="tertiary" tone="danger" onClick={onCancel}>
              {ui("Cancel")}
            </Button>
            {showSwitch && (
              <Button size="sm" disabled={!switchEnabled} onClick={onSwitch}>
                {ui("Chuyển index")}
              </Button>
            )}
          </div>
        </div>
        <div className="space-y-1.5 px-4">
          <Progress value={percent} aria-label={ui("Tiến độ dựng lại")} className="h-1" />
          <p className="flex flex-wrap items-center gap-x-1.5 font-secondary-body tabular-nums text-content-muted">
            {progress ? (
              <>
                <span>
                  {ui(
                    appText("{{ready}} / {{total}} tài liệu", {
                      ready: count(progress.ready),
                      total: count(progress.total),
                    }),
                  )}
                </span>
                <Dot />
                <span>{ui(appText("{{percent}}%", { percent }))}</span>
                <Dot />
                <span>{ui(remainingTime(progress.estimatedSecondsRemaining))}</span>
                {progress.failed > 0 && (
                  <>
                    <Dot />
                    <span>{ui(appText("{{count}} lỗi", { count: count(progress.failed) }))}</span>
                  </>
                )}
              </>
            ) : (
              <span>{ui("Đang chuẩn bị")}</span>
            )}
          </p>
        </div>
      </Card>
    </section>
  );
}

/** Separates the parts of one status line; hidden from screen readers, which pause on the spans. */
function Dot() {
  return (
    <span aria-hidden="true" className="text-content-muted">
      ·
    </span>
  );
}

function ProviderRow({
  provider,
  onEdit,
  onDelete,
}: {
  provider: EmbeddingProviderResponse;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <li aria-label={provider.name}>
      <Card size="sm" className="py-0">
        <div className="flex items-center gap-3 px-4 py-3">
          <span className={cn(providerTileClassName, "text-content-secondary")}>
            <Server className="size-4" aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="flex flex-wrap items-center gap-2">
              <span className="break-words font-main-ui-action">{provider.name}</span>
              {provider.inUse && <Badge variant="secondary">{ui("Đang dùng")}</Badge>}
              <DataBoundaryTag boundary={provider.dataBoundary} />
              {!provider.hasApiKey && (
                <StatusBadge tone="neutral">{ui("Không có khóa")}</StatusBadge>
              )}
            </p>
            <p className="break-all font-secondary-body text-content-muted">{provider.endpoint}</p>
          </div>
          <span className="flex shrink-0 items-center gap-1">
            <IconButton
              prominence="tertiary"
              size="sm"
              aria-label={ui(appText("Sửa provider {{name}}", { name: provider.name }))}
              onClick={onEdit}
            >
              <Settings2 />
            </IconButton>
            <IconButton
              prominence="tertiary"
              tone="danger"
              size="sm"
              aria-label={ui(appText("Xoá provider {{name}}", { name: provider.name }))}
              onClick={onDelete}
            >
              <Trash2 />
            </IconButton>
          </span>
        </div>
      </Card>
    </li>
  );
}

function Confirm({
  confirmation,
  onClose,
  onDone,
}: {
  confirmation: Confirmation;
  onClose: () => void;
  onDone: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const common = {
    open: true,
    onOpenChange: (open: boolean) => {
      if (!open) onClose();
    },
  };
  switch (confirmation.kind) {
    case "switch":
      return (
        <ConfirmDialog
          {...common}
          title={ui(appText("Chuyển sang {{model}}?", { model: confirmation.future.model }))}
          description={ui("Index đang dùng được giữ 7 ngày để hoàn tác.")}
          confirmTone="default"
          confirmLabel={ui("Chuyển index")}
          pendingLabel={ui("Đang chuyển")}
          onConfirm={async () => {
            await switchSearchFutureGeneration({
              headers: sameOriginMutationHeaders,
              throwOnError: true,
            });
            await onDone();
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "switch")}
        />
      );
    case "cancel":
      return (
        <ConfirmDialog
          {...common}
          title={ui("Hủy dựng lại?")}
          description={ui(
            appText("Index {{model}} đang dựng sẽ bị xoá.", { model: confirmation.future.model }),
          )}
          confirmLabel={ui("Hủy dựng lại")}
          pendingLabel={ui("Đang hủy")}
          onConfirm={async () => {
            await cancelSearchFutureGeneration({
              headers: sameOriginMutationHeaders,
              throwOnError: true,
            });
            await onDone();
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "cancel")}
        />
      );
    case "restore":
      return (
        <ConfirmDialog
          {...common}
          title={ui(appText("Hoàn tác về {{model}}?", { model: confirmation.past.model }))}
          description={ui("Tìm kiếm dùng lại index này ngay.")}
          confirmTone="default"
          confirmLabel={ui("Hoàn tác")}
          pendingLabel={ui("Đang hoàn tác")}
          onConfirm={async () => {
            await restoreSearchPastGeneration({
              path: { generationId: confirmation.past.id },
              headers: sameOriginMutationHeaders,
              throwOnError: true,
            });
            await onDone();
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "restore")}
        />
      );
    case "deleteProvider":
      return (
        <ConfirmDialog
          {...common}
          title={ui(appText("Xoá provider {{name}}?", { name: confirmation.provider.name }))}
          description={ui("Key đã lưu bị xoá cùng provider.")}
          confirmLabel={ui("Xoá provider")}
          pendingLabel={ui("Đang xoá")}
          onConfirm={async () => {
            await deleteEmbeddingProvider({
              path: { providerId: confirmation.provider.id },
              headers: sameOriginMutationHeaders,
              throwOnError: true,
            });
            await onDone();
          }}
          errorMessage={(cause) => searchSettingsProblem(cause, "deleteProvider")}
        />
      );
  }
}
