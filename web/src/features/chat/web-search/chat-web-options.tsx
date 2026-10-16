import { useQuery } from "@tanstack/react-query";
import { Check, Globe } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { getChatWebAvailabilityOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { menuRow } from "@/components/composites/menu-row";
import { webUsableOn, type WebSearchMode } from "./chat-web-preference";

type WebOptionsProps = {
  value: WebSearchMode;
  onChange: (mode: WebSearchMode) => void;
  /** Closes the surrounding menu after a choice. */
  onDone: () => void;
  sessionId?: string;
  modelId?: string;
};

function useWebSupport(sessionId?: string, modelId?: string) {
  const available = useQuery({
    ...getChatWebAvailabilityOptions({ query: { sessionId } }),
    retry: false,
  });
  const selectedModel = modelId ?? available.data?.inheritedModelId;
  // Provider-hosted search belongs to the model itself and needs no external search connection.
  const nativeSearch = !!(selectedModel && available.data?.nativeModelIds?.includes(selectedModel));
  const supported = (mode: WebSearchMode) =>
    mode === "off" || webUsableOn(available.data, selectedModel);
  return { available, nativeSearch, supported };
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
  // The organization can search, so the row is off because of the model: it neither hosts search nor calls tools.
  if (!support.available.isPending && !support.supported("auto"))
    return (
      <p className="px-2 py-1 text-xs text-content-muted">
        {ui("Mô hình đang chọn không dùng được tìm kiếm Web.")}
      </p>
    );
  return null;
}

/**
 * The Web row of the composer menu: one toggle for the next turn, marked as Claude marks Web search in its `+` menu.
 * Web has only Off and Auto, so the row itself is the whole choice.
 */
export function ChatWebToggle({ value, onChange, onDone, sessionId, modelId }: WebOptionsProps) {
  const ui = useAppTranslation();
  const support = useWebSupport(sessionId, modelId);
  return (
    <>
      <button
        type="button"
        aria-pressed={value !== "off"}
        disabled={value === "off" && !support.supported("auto")}
        className={menuRow}
        onClick={() => {
          onChange(value === "off" ? "auto" : "off");
          onDone();
        }}
      >
        <Globe aria-hidden="true" />
        <span className="flex-1">{ui("Tìm kiếm Web")}</span>
        {value !== "off" && <Check aria-hidden="true" />}
      </button>
      <WebAvailabilityNote support={support} />
    </>
  );
}
