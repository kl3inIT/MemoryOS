import { Section } from "@/components/section";
import { deployment } from "@/content";

// One option to a row, its title beside its description from lg, like the capabilities list.
function Deployment() {
  return (
    <Section id="deployment" title={deployment.title} description={deployment.description}>
      <ul className="border-b border-border-subtle">
        {deployment.options.map((option) => (
          <li
            key={option.title}
            className="grid gap-3 border-t border-border-subtle py-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)] lg:gap-16 lg:py-10"
          >
            <h3 className="font-title text-content-primary">{option.title}</h3>
            <p className="max-w-2xl font-lead text-content-secondary">{option.description}</p>
          </li>
        ))}
      </ul>
    </Section>
  );
}

export { Deployment };
