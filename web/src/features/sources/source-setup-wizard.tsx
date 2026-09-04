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

      <div className="mt-7 grid gap-8 lg:grid-cols-[12rem_minmax(0,1fr)]">
        <aside>
          <div className="flex items-center gap-3">
            <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-raised text-content-primary ring-1 ring-border-subtle">
              <ProviderIcon className="size-4" aria-hidden="true" />
            </span>
            <span className="text-sm font-medium text-content-primary">{provider.name}</span>
          </div>

          <ol aria-label="Setup progress" className="mt-5">
            {steps.map((step, index) => {
              const active = index === currentIndex;
              const complete = index < currentIndex;
              return (
                <li
                  key={step.id}
                  aria-current={active ? "step" : undefined}
                  className="relative flex h-9 items-center gap-3"
                >
                  {index < steps.length - 1 ? (
                    <span
                      className="absolute top-[1.375rem] left-[0.3125rem] h-5 w-px bg-border-subtle"
                      aria-hidden="true"
                    />
                  ) : null}
                  <span
                    className={`relative z-10 grid size-3 shrink-0 place-items-center rounded-full border ${
                      active || complete
                        ? "border-content-primary bg-content-primary text-surface-base"
                        : "border-border-default bg-surface-base"
                    }`}
                    aria-hidden="true"
                  >
                    {complete ? <Check className="size-2" /> : null}
                  </span>
                  <span
                    className={`truncate text-sm ${
                      active ? "font-medium text-content-primary" : "text-content-muted"
                    }`}
                  >
                    {step.label}
                  </span>
                </li>
              );
            })}
          </ol>
        </aside>

        <main className="min-w-0 max-w-3xl overflow-hidden rounded-xl border border-border-subtle bg-surface-raised">
          <header className="border-b border-border-subtle px-5 py-6 sm:px-7">
            <h1 className="font-heading-h2 text-content-primary">{activeStep.title}</h1>
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
            <div className="px-5 py-6 sm:px-7">{activeStep.content}</div>

            {error ? (
              <p
                role="alert"
                className="mx-5 mb-5 rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content sm:mx-7"
              >
                {error}
              </p>
            ) : null}

            <footer className="grid grid-cols-3 items-center gap-3 border-t border-border-subtle px-5 py-4 sm:px-7">
              <div className="justify-self-start">
                {currentIndex === 0 ? (
                  <Button asChild prominence="tertiary" disabled={pending}>
                    <Link to="/admin/sources/new">Previous</Link>
                  </Button>
                ) : (
                  <Button prominence="tertiary" onClick={previousStep} disabled={pending}>
                    Previous
                  </Button>
                )}
              </div>
              <div className="justify-self-center">
                {isLastStep ? (
                  <Button
                    type="submit"
                    pending={pending}
                    disabled={!activeStep.canContinue || pending}
                  >
                    {activeStep.completionLabel ?? "Create"}
                  </Button>
                ) : null}
              </div>
              <div className="justify-self-end">
                {!isLastStep ? (
                  <Button
                    type="submit"
                    pending={pending}
                    disabled={!activeStep.canContinue || pending}
                  >
                    Continue
                  </Button>
                ) : null}
              </div>
            </footer>
          </form>
        </main>
      </div>
    </section>
  );
}
