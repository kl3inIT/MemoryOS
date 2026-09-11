import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

type SectionProps = {
  id: string;
  title: string;
  description?: string;
  className?: string;
  children: ReactNode;
};

function Section({ id, title, description, className, children }: SectionProps) {
  const headingId = `${id}-heading`;

  return (
    <section
      id={id}
      aria-labelledby={headingId}
      className={cn("scroll-mt-16 px-[var(--page-gutter)] py-20 sm:py-28", className)}
    >
      <div className="mx-auto w-full max-w-[var(--page-width-wide)]">
        <div className="max-w-2xl">
          <h2 id={headingId} className="font-heading-section text-content-primary">
            {title}
          </h2>
          {description ? (
            <p className="mt-4 font-lead text-content-secondary">{description}</p>
          ) : null}
        </div>
        <div className="mt-12 sm:mt-16">{children}</div>
      </div>
    </section>
  );
}

export { Section };
