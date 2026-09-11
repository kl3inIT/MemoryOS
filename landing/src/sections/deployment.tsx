import { Section } from "@/components/section";
import { deployment } from "@/content";

function Deployment() {
  return (
    <Section id="deployment" title={deployment.title} description={deployment.description}>
      <ul className="grid gap-4 md:grid-cols-3">
        {deployment.options.map(({ title, description, icon: Icon }) => (
          <li
            key={title}
            className="reveal rounded-xl border border-border-subtle bg-surface-raised p-5 sm:p-6"
          >
            <Icon aria-hidden="true" className="size-5 text-accent" />
            <h3 className="mt-4 font-heading-h3 text-content-primary">{title}</h3>
            <p className="mt-2 font-main-content-body text-content-secondary">{description}</p>
          </li>
        ))}
      </ul>
    </Section>
  );
}

export { Deployment };
