type BrandProps = {
  compact?: boolean;
};

export function Brand({ compact = false }: BrandProps) {
  return (
    <span className="inline-flex items-center gap-3" aria-label="MemoryOS">
      <span
        className="grid size-7 grid-cols-2 gap-px bg-neutral-950 p-1.5 text-white"
        aria-hidden="true"
      >
        <span className="bg-current" />
        <span className="bg-current opacity-45" />
        <span className="bg-current opacity-45" />
        <span className="bg-current" />
      </span>
      {!compact && (
        <span className="text-[0.95rem] font-semibold tracking-[-0.035em] text-neutral-950">
          MemoryOS
        </span>
      )}
    </span>
  );
}
