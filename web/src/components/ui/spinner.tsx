import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { Loader2Icon } from "lucide-react";

/** A loading indicator; with `aria-hidden` it is decorative (inside a busy control) and announces nothing. */
function Spinner({ className, ...props }: React.ComponentProps<"svg">) {
  const ui = useAppTranslation();
  const decorative = props["aria-hidden"] === true || props["aria-hidden"] === "true";
  return (
    <Loader2Icon
      data-slot="spinner"
      role={decorative ? undefined : "status"}
      aria-label={decorative ? undefined : ui("Đang tải…")}
      className={cn("size-4 animate-spin motion-reduce:animate-none", className)}
      {...props}
    />
  );
}

export { Spinner };
