import type { ComponentProps, ComponentType } from "react";

export function SourceSectionIcon({ icon: Icon }: { icon: ComponentType<ComponentProps<"svg">> }) {
  return (
    <span
      aria-hidden="true"
      className="grid size-10 shrink-0 place-items-center rounded-lg bg-surface-subtle text-content-secondary"
    >
      <Icon className="size-5" />
    </span>
  );
}
