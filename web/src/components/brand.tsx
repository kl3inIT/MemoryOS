type BrandProps = {
  compact?: boolean;
};

export function Brand({ compact = false }: BrandProps) {
  return (
    <span className="inline-flex items-center gap-2.5" aria-label="MemoryOS">
      <span
        className="grid size-7 grid-cols-2 gap-px rounded-md border border-border-default bg-surface-raised p-1.5 text-content-primary shadow-xs"
        aria-hidden="true"
      >
        <span className="bg-current" />
        <span className="bg-current opacity-45" />
        <span className="bg-current opacity-45" />
        <span className="bg-current" />
      </span>
      {!compact && <span className="font-main-ui-action text-content-primary">MemoryOS</span>}
    </span>
  );
}
