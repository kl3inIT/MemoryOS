import { organization } from "@/content";
import { cn } from "@/lib/utils";

type VadanLogoProps = {
  className?: string;
};

// Vadan's wordmark as a single-colour mask, like the partner logos, in the color of the text around it.
function VadanLogo({ className }: VadanLogoProps) {
  return (
    <span
      role="img"
      aria-label={organization.name}
      className={cn("logo-mask inline-block aspect-[374/96] bg-current", className)}
      style={{ maskImage: `url(${organization.logo})` }}
    />
  );
}

export { VadanLogo };
