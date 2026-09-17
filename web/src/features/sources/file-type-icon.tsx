import { Icon } from "@iconify/react";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { fileTypeOf } from "./file-types";
import { SourceHint } from "./source-hint";

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
    <SourceHint hint={label}>
      <span className="mt-0.5 shrink-0">
        {/* Bundled icon data renders on the first paint rather than after mount. */}
        <Icon
          icon={type.icon}
          ssr
          aria-hidden="true"
          className="size-4"
          style={type.color ? { color: type.color } : undefined}
        />
        <span className="sr-only">{label}: </span>
      </span>
    </SourceHint>
  );
}
