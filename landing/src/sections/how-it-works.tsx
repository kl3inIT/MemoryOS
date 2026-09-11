import { Section } from "@/components/section";
import { howItWorks } from "@/content";

function HowItWorks() {
  const { steps, request } = howItWorks;

  return (
    <Section id="how-it-works" title={howItWorks.title} description={howItWorks.description}>
      <ol className="grid gap-10 sm:grid-cols-2 lg:grid-cols-4 lg:gap-8">
        {steps.map((step, index) => (
          <li key={step.title} className="border-t-2 border-accent pt-5">
            <p className="font-main-ui-action text-accent">Step {index + 1}</p>
            <h3 className="mt-2 font-heading-h3 text-content-primary">{step.title}</h3>
            <p className="mt-2 font-main-content-body text-content-secondary">{step.description}</p>
          </li>
        ))}
      </ol>
      <div className="mt-16 rounded-2xl bg-surface-canvas p-6 sm:p-8">
        <h3 className="font-heading-h3 text-content-primary">{request.title}</h3>
        <ol className="mt-6 grid gap-4 md:grid-cols-3">
          {request.steps.map((step, index) => (
            <li
              key={step.title}
              className="rounded-xl border border-border-subtle bg-surface-raised p-5"
            >
              <span className="inline-flex size-6 items-center justify-center rounded-full bg-accent-surface font-secondary-action text-accent-content">
                {index + 1}
              </span>
              <p className="mt-3 font-main-ui-action text-content-primary">{step.title}</p>
              <p className="mt-1 font-main-ui-body text-content-secondary">{step.description}</p>
            </li>
          ))}
        </ol>
      </div>
    </Section>
  );
}

export { HowItWorks };
