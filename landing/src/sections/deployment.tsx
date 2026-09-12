import { Section } from "@/components/section";
import { deployment } from "@/content";

function Deployment() {
  return (
    <Section id="deployment" title={deployment.title} description={deployment.description}>
      <ul className="grid gap-10 md:grid-cols-3 md:gap-8">
        {deployment.options.map((option) => (
          <li key={option.title} className="border-t border-border-default pt-6">
            <h3 className="font-heading-h3 text-content-primary">{option.title}</h3>
            <p className="mt-2 font-main-content-body text-content-secondary">
              {option.description}
            </p>
          </li>
        ))}
      </ul>
    </Section>
  );
}

export { Deployment };
