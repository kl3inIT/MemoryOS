import { Check, ImagePlus } from "lucide-react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { composerMenuRow } from "./chat-composer-menu-row";
import type { ImageMode } from "./chat-image";

/** The image-generation row of the composer menu: enables image generation for the next turn. */
export function ChatImageToggle({
  value,
  onChange,
  onDone,
}: {
  value: ImageMode;
  onChange: (mode: ImageMode) => void;
  onDone: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <button
      type="button"
      aria-pressed={value !== "off"}
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
}
