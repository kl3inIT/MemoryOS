import type { ComponentProps, ComponentType } from "react";

export function SourceSectionIcon({ icon: Icon }: { icon: ComponentType<ComponentProps<"svg">> }) {
  return (
    <span
      aria-hidden="true"
      className="grid size-10 shrink-0 place-items-center text-content-muted"
    >
      <Icon className="size-5" />
    </span>
  );
}
