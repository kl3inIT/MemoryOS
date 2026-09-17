import { useContext } from "react";
import { Check, ImagePlus } from "lucide-react";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { ApplicationSessionContext } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { composerMenuRow } from "./chat-composer-menu-row";
import type { ImageMode } from "./chat-image";

/** The image-generation row of the composer menu: enables image generation for the next turn. */
export function ChatImageToggle({
  value,
  onChange,
  onDone,
  available = true,
}: {
  value: ImageMode;
  onChange: (mode: ImageMode) => void;
  onDone: () => void;
  /** False while no usable image provider exists; the row stays visible but disabled with a hint. */
  available?: boolean;
}) {
  const ui = useAppTranslation();
  // Direct context read: without a session the row simply shows the non-manager hint.
  const manager =
    useContext(ApplicationSessionContext)?.capabilities.includes("MODELS_MANAGE") === true;
  const row = (
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
  );
  if (available) return row;
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <span tabIndex={0} className="w-full">
            {row}
          </span>
        </TooltipTrigger>
        <TooltipContent side="right">
          {manager
            ? ui("Chưa có nhà cung cấp tạo ảnh — thêm mô hình trong Quản trị › Tạo ảnh.")
            : ui("Tạo ảnh chưa được bật. Liên hệ quản trị viên để thêm mô hình tạo ảnh.")}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
