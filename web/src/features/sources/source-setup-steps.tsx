import { useAppTranslation } from "@/i18n/use-app-translation";
import { Check } from "lucide-react";
import { cn } from "@/lib/utils";

export function SourceSetupSteps({
  steps,
  current,
}: {
  steps: readonly { label: string; complete?: boolean }[];
  current: number;
}) {
  const ui = useAppTranslation();

  return (
    <nav aria-label={ui("Source setup")} className="shrink-0 md:w-44">
      <ol className="flex gap-2 md:flex-col md:gap-0">
        {steps.map((step, index) => (
          <li
            key={step.label}
            aria-current={current === index ? "step" : undefined}
            className="relative min-w-0 flex-1 md:flex-none"
          >
            {index < steps.length - 1 ? (
              <span
                aria-hidden="true"
                className="absolute left-4 top-7 hidden h-8 w-px bg-border-default md:block"
              />
            ) : null}
            <div
              className={cn(
                "relative flex min-h-16 w-full flex-col items-center justify-center gap-1 px-1 text-center text-sm md:min-h-12 md:flex-row md:justify-start md:gap-2 md:px-2 md:text-left",
                current === index ? "font-medium text-content-primary" : "text-content-muted",
              )}
            >
              <span
                className={cn(
                  "grid size-5 shrink-0 place-items-center rounded-full border text-xs",
                  current === index || step.complete
                    ? "border-content-primary bg-content-primary text-surface-raised"
                    : "border-border-default bg-surface-base",
                )}
              >
                {step.complete ? <Check className="size-3" aria-hidden="true" /> : index + 1}
              </span>
              <span>{ui(step.label)}</span>
            </div>
          </li>
        ))}
      </ol>
    </nav>
  );
}
