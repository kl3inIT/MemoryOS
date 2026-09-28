import type { ComponentProps, ComponentType } from "react";

export function SourceSectionIcon({ icon: Icon }: { icon: ComponentType<ComponentProps<"svg">> }) {
  return (
    <span aria-hidden="true" className="shrink-0 text-content-secondary">
      <Icon className="size-5" />
    </span>
  );
}
