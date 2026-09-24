import { Check, ImagePlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { composerMenuRow } from "@/features/chat/composer/chat-composer-menu-row";
import type { ImageMode } from "./chat-image";

/** The image-generation row of the composer menu: enables image generation for the next turn. */
export function ChatImageToggle({
  value,
  onChange,
  onDone,
  available = true,
  pending = false,
  onRetry,
}: {
  value: ImageMode;
  onChange: (mode: ImageMode) => void;
  onDone: () => void;
  /** False while no usable image provider connection exists. */
  available?: boolean;
  /** Availability still loading: the row stays disabled without a notice. */
  pending?: boolean;
  /** Availability fetch failed; offers a retry instead of the notice. */
  onRetry?: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <>
      <button
        type="button"
        aria-pressed={value !== "off"}
        disabled={!available}
        className={composerMenuRow}
        onClick={() => {
          onChange(value === "off" ? "auto" : "off");
          onDone();
        }}
      >
        <ImagePlus aria-hidden="true" />
        <span className="flex-1">{ui("Tạo ảnh")}</span>
        {value !== "off" && <Check aria-hidden="true" />}
      </button>
      {!available &&
        (onRetry ? (
          <Button size="sm" prominence="internal" onClick={onRetry}>
            {ui("Tải lại")}
          </Button>
        ) : pending ? null : (
          <p className="px-2 py-1 text-xs text-content-muted">
            {ui("Chưa kết nối mô hình tạo ảnh.")}
          </p>
        ))}
    </>
  );
}
