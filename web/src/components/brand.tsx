/** The Tenant this deployment serves. A second Tenant makes this its own setting. */
const TENANT_NAME = "Tasco";

type BrandProps = {
  compact?: boolean;
};

/**
 * The Tenant's wordmark, painted in the surrounding text colour.
 *
 * The artwork is a black silhouette, so it is masked rather than drawn: one file serves the
 * sidebar in either theme, and it follows the colour around it instead of carrying its own.
 * The collapsed rail is as wide as a button, where a wordmark would be a smudge, so it shows the
 * wordmark's own "T" (tenant-mark.png, cut from the same artwork) in the tile.
 */
export function Brand({ compact = false }: BrandProps) {
  const name = TENANT_NAME;

  if (compact) {
    return (
      <span
        role="img"
        className="grid size-7 place-items-center rounded-md border border-border-default bg-surface-raised"
        aria-label={name}
      >
        <span
          aria-hidden="true"
          className="size-4 bg-content-primary"
          style={{
            maskImage: "url(/tenant-mark.png)",
            WebkitMaskImage: "url(/tenant-mark.png)",
            maskRepeat: "no-repeat",
            WebkitMaskRepeat: "no-repeat",
            maskPosition: "center",
            WebkitMaskPosition: "center",
            maskSize: "contain",
            WebkitMaskSize: "contain",
          }}
        />
      </span>
    );
  }

  return (
    <span
      role="img"
      aria-label={name}
      className="inline-block h-4 w-[6.9rem] bg-content-primary"
      style={{
        maskImage: "url(/tenant-wordmark.png)",
        WebkitMaskImage: "url(/tenant-wordmark.png)",
        maskRepeat: "no-repeat",
        WebkitMaskRepeat: "no-repeat",
        maskPosition: "left center",
        WebkitMaskPosition: "left center",
        maskSize: "contain",
        WebkitMaskSize: "contain",
      }}
    />
  );
}
