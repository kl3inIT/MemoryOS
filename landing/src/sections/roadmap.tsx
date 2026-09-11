import { Section } from "@/components/section";
import { roadmap } from "@/content";

function Roadmap() {
  return (
    <Section id="roadmap" title={roadmap.title} description={roadmap.description}>
      <ol className="grid gap-10 lg:grid-cols-4 lg:gap-8">
        {roadmap.milestones.map((milestone) => (
          <li
            key={milestone.dateTime}
            className="relative border-l border-border-default pl-6 lg:border-t lg:border-l-0 lg:pt-6 lg:pl-0"
          >
            <span
              aria-hidden="true"
              className="absolute top-1.5 -left-[5.5px] size-2.5 rounded-full bg-accent lg:-top-[5.5px] lg:left-0"
            />
            <time dateTime={milestone.dateTime} className="font-main-ui-action text-content-muted">
              {milestone.period}
            </time>
            <h3 className="mt-2 font-heading-h3 text-content-primary">{milestone.title}</h3>
            <p className="mt-2 font-main-content-body text-content-secondary">
              {milestone.description}
            </p>
          </li>
        ))}
      </ol>
      <div className="mt-20">
        <h3 className="font-heading-h2 text-content-primary">{roadmap.next.title}</h3>
        <ul className="mt-6 grid gap-px overflow-hidden rounded-2xl border border-border-subtle bg-border-subtle sm:grid-cols-2 lg:grid-cols-4">
          {roadmap.next.items.map((item) => (
            <li key={item.title} className="bg-surface-raised p-6">
              <p className="font-heading-h3 text-content-primary">{item.title}</p>
              <p className="mt-2 font-main-content-body text-content-secondary">
                {item.description}
              </p>
            </li>
          ))}
        </ul>
      </div>
    </Section>
  );
}

export { Roadmap };
