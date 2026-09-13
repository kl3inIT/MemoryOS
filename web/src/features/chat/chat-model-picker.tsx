import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  ModelSelectorRoot,
  ModelSelectorTrigger,
  ModelSelectorContent,
  ModelSelectorGroup,
  ModelSelectorItem,
  ModelSelectorSearch,
  ModelSelectorList,
  ModelSelectorEmpty,
} from "@/components/assistant-ui/elements/model-selector";
import { Button } from "@/components/ui/button";
import { useChatModels } from "./chat-models";

export function ChatModelPicker({
  sessionId,
  value,
  onChange,
  disabled,
}: {
  sessionId?: string;
  value?: string;
  onChange: (id?: string) => void;
  disabled: boolean;
}) {
  const ui = useAppTranslation();

  const { catalog, models } = useChatModels(sessionId);
  if (catalog.isError)
    return (
      <Button size="sm" prominence="internal" onClick={() => void catalog.refetch()}>
        {ui("Tải lại mô hình")}
      </Button>
    );
  // Undefined remains backend inheritance, not a synthetic selectable model.
  const inheritedId = catalog.data?.find((model) => model.isDefault)?.id;
  const selectedId = value ?? inheritedId;
  return (
    <ModelSelectorRoot models={models} value={selectedId ?? ""} onValueChange={onChange}>
      <ModelSelectorTrigger
        aria-label={ui("Chọn mô hình")}
        variant="ghost"
        size="sm"
        disabled={disabled || catalog.isPending || models.length === 0}
        className="min-w-0 max-w-[min(18rem,50vw)] text-content-secondary"
      >
        {catalog.isPending
          ? ui("Đang tải mô hình…")
          : models.length === 0
            ? ui("Chưa có mô hình khả dụng")
            : !selectedId || !models.some((model) => model.id === selectedId)
              ? ui("Mô hình đã chọn không khả dụng")
              : undefined}
      </ModelSelectorTrigger>
      <ModelSelectorContent className="w-80 max-w-[calc(100vw-2rem)]" align="end">
        <ModelSelectorSearch aria-label={ui("Tìm mô hình")} placeholder={ui("Tìm mô hình…")} />
        <ModelSelectorList>
          <ModelSelectorEmpty>{ui("Không tìm thấy mô hình.")}</ModelSelectorEmpty>
          <ModelSelectorGroup>
            {models.map((model) => (
              <ModelSelectorItem key={model.id} model={model} />
            ))}
          </ModelSelectorGroup>
        </ModelSelectorList>
      </ModelSelectorContent>
    </ModelSelectorRoot>
  );
}
