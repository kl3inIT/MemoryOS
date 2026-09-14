import { providerMarks, type ProviderMark } from "@/components/provider-logos/provider-marks";
import { cn } from "@/lib/utils";

export function ProviderLogo({ mark, className }: { mark: ProviderMark; className?: string }) {
  const { file, monochrome } = providerMarks[mark];
  return (
    <img
      src={`/provider-logos/${file}`}
      alt=""
      aria-hidden="true"
      className={cn("size-6 shrink-0 object-contain", monochrome && "dark:invert", className)}
    />
  );
}
