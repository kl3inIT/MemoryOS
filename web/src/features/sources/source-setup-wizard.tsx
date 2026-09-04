import { Link } from "@tanstack/react-router";
import { useRef, type ReactNode } from "react";
import { ArrowLeft, Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { SourceProvider } from "./source-provider-catalog";

export type SourceSetupStep<StepId extends string> = {
  id: StepId;
  label: string;
  title: string;
  description: string;
  content: ReactNode;
  canContinue: boolean;
  completionLabel?: string;
};

type SourceSetupWizardProps<StepId extends string> = {
  provider: SourceProvider;
  steps: readonly [SourceSetupStep<StepId>, ...SourceSetupStep<StepId>[]];
  activeStepId: StepId;
  onActiveStepChange: (stepId: StepId) => void;
  onComplete: () => void | Promise<void>;
  pending?: boolean;
  error?: string | null;
};

export function SourceSetupWizard<StepId extends string>({
  provider,
  steps,
  activeStepId,
  onActiveStepChange,
  onComplete,
  pending = false,
  error,
}: SourceSetupWizardProps<StepId>) {
  const completionInFlight = useRef(false);
  const currentIndex = Math.max(
    0,
    steps.findIndex((step) => step.id === activeStepId),
  );
  const activeStep = steps[currentIndex] ?? steps[0];
  const isLastStep = currentIndex === steps.length - 1;
  const ProviderIcon = provider.icon;

  function previousStep() {
    const previous = steps[currentIndex - 1];
    if (previous) onActiveStepChange(previous.id);
  }

  async function submit() {
    if (!activeStep.canContinue || pending || completionInFlight.current) return;
    const next = steps[currentIndex + 1];
    if (next) {
      onActiveStepChange(next.id);
      return;
    }
    completionInFlight.current = true;
    try {
      await onComplete();
    } finally {
      completionInFlight.current = false;
    }
  }

  return (
    <section className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8 sm:py-12">
      <Link
        to="/admin/sources/new"
        className="inline-flex items-center gap-2 font-secondary-action text-content-secondary transition-colors hover:text-content-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        Source catalog
      </Link>

      <div className="mt-6 overflow-hidden rounded-2xl border border-border-subtle bg-surface-raised lg:grid lg:grid-cols-[17rem_minmax(0,1fr)]">
        <aside className="border-b border-border-subtle bg-surface-subtle p-5 sm:p-6 lg:border-r lg:border-b-0">
          <div className="flex items-center gap-3">
            <span className="grid size-11 shrink-0 place-items-center rounded-xl border border-border-subtle bg-surface-raised text-content-primary">
              <ProviderIcon className="size-5" aria-hidden="true" />
            </span>
            <div className="min-w-0">
              <p className="font-secondary-body text-content-muted">Source type</p>
              <p className="truncate font-heading-h3 text-content-primary">{provider.name}</p>
            </div>
          </div>

          <ol aria-label="Setup progress" className="mt-7 grid gap-2 sm:grid-cols-2 lg:grid-cols-1">
            {steps.map((step, index) => {
              const active = index === currentIndex;
              const complete = index < currentIndex;
              return (
                <li
                  key={step.id}
                  aria-current={active ? "step" : undefined}
                  className={`flex items-start gap-3 rounded-xl px-3 py-3 ${
                    active ? "bg-surface-raised text-content-primary" : "text-content-muted"
                  }`}
                >
                  <span
                    className={`grid size-7 shrink-0 place-items-center rounded-lg border font-secondary-action tabular-nums ${
                      active || complete
                        ? "border-border-default bg-surface-sunken text-content-primary"
                        : "border-border-subtle"
                    }`}
                    aria-hidden="true"
                  >
                    {complete ? <Check className="size-3.5" /> : index + 1}
                  </span>
                  <span className="pt-1 text-sm font-medium">{step.label}</span>
                </li>
              );
            })}
          </ol>
        </aside>

        <main className="min-w-0">
          <header className="border-b border-border-subtle px-5 py-6 sm:px-8 sm:py-8">
            <p className="font-secondary-action text-content-muted">
              Step {currentIndex + 1} of {steps.length}
            </p>
            <h1 className="mt-2 font-heading-h2 text-content-primary">{activeStep.title}</h1>
            <p className="mt-2 max-w-2xl font-main-ui-body text-content-secondary">
              {activeStep.description}
            </p>
          </header>

          <form
            onSubmit={(event) => {
              event.preventDefault();
              void submit();
            }}
          >
            <div className="px-5 py-6 sm:px-8 sm:py-8">{activeStep.content}</div>

            {error ? (
              <p
                role="alert"
                className="mx-5 mb-5 rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content sm:mx-8"
              >
                {error}
              </p>
            ) : null}

            <footer className="flex items-center justify-between gap-3 border-t border-border-subtle bg-surface-subtle px-5 py-4 sm:px-8">
              {currentIndex === 0 ? (
                <Button asChild prominence="tertiary" disabled={pending}>
                  <Link to="/admin/sources/new">Back</Link>
                </Button>
              ) : (
                <Button prominence="tertiary" onClick={previousStep} disabled={pending}>
                  Back
                </Button>
              )}
              <Button type="submit" pending={pending} disabled={!activeStep.canContinue || pending}>
                {isLastStep ? (activeStep.completionLabel ?? "Connect source") : "Continue"}
              </Button>
            </footer>
          </form>
        </main>
      </div>
    </section>
  );
}
