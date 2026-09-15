import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ArrowLeft, Check, ChevronRight, Globe, Settings } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { getChatWebAvailability } from "@/lib/hey-api/sdk.gen";
import { cn } from "@/lib/utils";
import { composerMenuRow } from "./chat-composer-menu-row";
import type { WebSearchMode } from "./chat-web-preference";

type WebOptionsProps = {
  value: WebSearchMode;
  onChange: (mode: WebSearchMode) => void;
  /** Closes the surrounding menu after a choice. */
  onDone: () => void;
  sessionId?: string;
  modelId?: string;
};

function useWebSupport(sessionId?: string, modelId?: string) {
  const session = useApplicationSession();
  const available = useQuery({
    queryKey: ["chat-web", session.actorId, session.authorizationVersion, sessionId],
    queryFn: async ({ signal }) =>
      (await getChatWebAvailability({ query: { sessionId }, signal, throwOnError: true })).data,
    retry: false,
  });
  const selectedModel = modelId ?? available.data?.inheritedModelId;
  // Provider-hosted search belongs to the model itself and needs no external search connection.
  const nativeSearch = !!(selectedModel && available.data?.nativeModelIds?.includes(selectedModel));
  const supported = (mode: WebSearchMode) =>
    mode === "off" ||
    nativeSearch ||
    !!(
      available.data?.searchAvailable &&
      selectedModel &&
      available.data.automaticModelIds?.includes(selectedModel)
    );
  return {
    available,
    nativeSearch,
    supported,
    canManage: session.capabilities.includes("MODELS_MANAGE"),
  };
}

function WebAvailabilityNote({ support }: { support: ReturnType<typeof useWebSupport> }) {
  const ui = useAppTranslation();
  if (support.available.isError)
    return (
      <Button size="sm" prominence="internal" onClick={() => void support.available.refetch()}>
        {ui("Tải lại")}
      </Button>
    );
  if (
    !support.available.isPending &&
    !support.available.data?.searchAvailable &&
    !support.nativeSearch
  )
    return (
      <p className="px-2 py-1 text-xs text-content-muted">{ui("Chưa kết nối công cụ tìm kiếm.")}</p>
    );
  return null;
}

/** The Web row of the composer menu: toggles Web for the next turn and opens its options. */
export function ChatWebToggle({
  onConfigure,
  value,
  onChange,
  onDone,
  sessionId,
  modelId,
}: WebOptionsProps & { onConfigure: () => void }) {
  const ui = useAppTranslation();
  const support = useWebSupport(sessionId, modelId);
  return (
    <>
      <div className="flex items-center gap-1">
        <button
          type="button"
          aria-pressed={value !== "off"}
          disabled={value === "off" && !support.supported("auto")}
          className={cn(composerMenuRow, "flex-1")}
          onClick={() => {
            onChange(value === "off" ? "auto" : "off");
            onDone();
          }}
        >
          <Globe aria-hidden="true" />
          <span className="flex-1">{ui("Tìm kiếm Web")}</span>
          {value !== "off" && <Check aria-hidden="true" />}
        </button>
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Tùy chọn Web")}
          title={ui("Tùy chọn Web")}
          onClick={onConfigure}
        >
          <ChevronRight />
        </IconButton>
      </div>
      <WebAvailabilityNote support={support} />
    </>
  );
}

/** Off / Auto / Required choices, limited to what the selected model's adapters support. */
export function ChatWebModes({
  onBack,
  value,
  onChange,
  onDone,
  sessionId,
  modelId,
}: WebOptionsProps & { onBack: () => void }) {
  const ui = useAppTranslation();
  const support = useWebSupport(sessionId, modelId);
  const labels = {
    off: ui("Tắt Web"),
    auto: ui("Tự động dùng Web"),
  };
  const heading = ui("Tìm kiếm và đọc trang Web");
  return (
    <div className="flex flex-col gap-1">
      <Button
        size="sm"
        prominence="internal"
        aria-label={ui("Quay lại")}
        className="self-start"
        onClick={onBack}
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        {heading}
      </Button>
      <div role="radiogroup" aria-label={heading} className="flex flex-col">
        {(["off", "auto"] as const).map((mode) => (
          <button
            key={mode}
            type="button"
            role="radio"
            aria-checked={mode === value}
            disabled={!support.supported(mode)}
            className={composerMenuRow}
            onClick={() => {
              onChange(mode);
              onDone();
            }}
          >
            <span className="flex-1">{labels[mode]}</span>
            {mode === value && <Check aria-hidden="true" />}
          </button>
        ))}
      </div>
      <WebAvailabilityNote support={support} />
      {support.canManage && (
        <Link to="/admin/web-search" className={composerMenuRow} onClick={onDone}>
          <Settings aria-hidden="true" />
          {ui("Cài đặt Web")}
        </Link>
      )}
    </div>
  );
}
