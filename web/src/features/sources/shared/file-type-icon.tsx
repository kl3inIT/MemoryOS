import { Icon } from "@iconify/react";
import type { CSSProperties } from "react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { fileTypeOf } from "./file-types";

/** A file's type icon with the type named for pointer and assistive-technology users alike. */
export function FileTypeIcon({
  name,
  mimeType,
}: {
  name?: string | null;
  mimeType?: string | null;
}) {
  const ui = useAppTranslation();
  const type = fileTypeOf({ name, mimeType });
  const label = ui(type.label);
  return (
    // The pointer hint is a plain title: lists render thousands of these, and a tooltip component per icon is what makes a large tree crawl.
    // Positioned so the absolutely placed screen-reader label cannot stretch the page beyond its row.
    <span title={label} className="relative mt-0.5 shrink-0">
      {/* Bundled icon data renders on the first paint rather than after mount. */}
      <Icon
        icon={type.icon}
        ssr
        aria-hidden="true"
        // A brand colour arrives as data, so it reaches the icon through a custom property.
        className="size-4 text-(--file-type-color)"
        style={type.color ? ({ "--file-type-color": type.color } as CSSProperties) : undefined}
      />
      <span className="sr-only">{label}: </span>
    </span>
  );
}
