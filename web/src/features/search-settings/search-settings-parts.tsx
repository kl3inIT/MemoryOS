import { ArrowRight, Server, Settings2, Trash2 } from "lucide-react";
import type { ReactNode } from "react";
import { SectionHeader } from "@/components/composites/section-header";
import { providerTileClassName } from "@/components/provider-logos/provider-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { IconButton } from "@/components/ui/icon-button";
import { Progress } from "@/components/ui/progress";
import { StatusBadge } from "@/components/ui/status-badge";
import { DataBoundaryTag } from "@/features/models/data-boundary";
import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { EmbeddingProviderResponse } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import {
  rebuildPercent,
  remainingTime,
  useCount,
  type Generation,
  type RebuildProgress,
} from "./search-settings";

/** Model on the first line, provider and where the data goes on the second. */
export function GenerationIdentity({
  generation,
  badge,
}: {
  generation: Generation;
  badge?: ReactNode;
}) {
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

/** Separates the parts of one status line; hidden from screen readers, which pause on the spans. */
function Dot() {
  return (
    <span aria-hidden="true" className="text-content-muted">
      ·
    </span>
  );
}

export function RebuildSection({
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
    <section aria-labelledby="search-rebuild" className="flex flex-col gap-3">
      <SectionHeader id="search-rebuild" title={ui("Đang dựng lại")} />
      <Card size="sm">
        <CardContent>
          <div className="flex flex-col gap-3">
            {/* The actions wrap under the models on a phone instead of squeezing them. */}
            <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
              <div className="min-w-0 flex-1 basis-64">
                <p className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5">
                  {!sameModel && (
                    <span className="inline-flex min-w-0 items-center gap-1.5">
                      <span className="break-words font-main-ui-body text-content-muted">
                        {present.model}
                      </span>
                      <ArrowRight
                        className="size-3.5 shrink-0 text-content-muted"
                        aria-hidden="true"
                      />
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
            <div className="flex flex-col gap-1.5">
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
                        <span className="text-status-danger-content">
                          {ui(appText("{{count}} lỗi", { count: count(progress.failed) }))}
                        </span>
                      </>
                    )}
                  </>
                ) : (
                  <span>{ui("Đang chuẩn bị")}</span>
                )}
              </p>
            </div>
          </div>
        </CardContent>
      </Card>
    </section>
  );
}

export function ProviderRow({
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
      <Card size="sm">
        <CardContent>
          <div className="flex items-center gap-3">
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
              <p className="break-all font-secondary-body text-content-muted">
                {provider.endpoint}
              </p>
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
        </CardContent>
      </Card>
    </li>
  );
}
