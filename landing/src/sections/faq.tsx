import { ChevronDown } from "lucide-react";
import { Section } from "@/components/section";
import { faq } from "@/content";

function Faq() {
  return (
    <Section id="faq" title={faq.title} description={faq.description}>
      <div className="divide-y divide-border-subtle border-y border-border-subtle">
        {faq.items.map((item) => (
          <details key={item.question} className="reveal group">
            <summary className="flex cursor-pointer list-none items-center justify-between gap-6 rounded-md py-5 font-heading-h3 text-content-primary [&::-webkit-details-marker]:hidden">
              {item.question}
              <ChevronDown
                aria-hidden="true"
                className="size-5 shrink-0 text-content-muted transition-transform duration-150 group-open:rotate-180"
              />
            </summary>
            <p className="max-w-3xl pb-6 font-main-content-body text-content-secondary">
              {item.answer}
            </p>
          </details>
        ))}
      </div>
    </Section>
  );
}

export { Faq };
