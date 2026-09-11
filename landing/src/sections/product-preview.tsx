import { LockKeyhole } from "lucide-react";
import { BrandMark } from "@/components/brand-mark";
import { productPreview } from "@/content";

type CitationMarkerProps = {
  index: number;
};

function CitationMarker({ index }: CitationMarkerProps) {
  return (
    <span className="inline-flex size-5 shrink-0 items-center justify-center rounded-md bg-citation-surface align-text-bottom font-secondary-action text-citation-content">
      <span className="sr-only">Source </span>
      {index}
    </span>
  );
}

function ProductPreview() {
  const { label, question, assistantName, answer, sourcesLabel, citations, accessNote } =
    productPreview;

  return (
    <figure aria-label={label} className="rounded-2xl bg-surface-canvas p-3 sm:p-4">
      <div className="overflow-hidden rounded-xl border border-border-subtle bg-surface-raised shadow-md">
        <div className="space-y-5 p-5 sm:p-6">
          <p className="ml-auto w-fit max-w-[85%] rounded-2xl rounded-br-md bg-surface-canvas px-4 py-2.5 font-main-content-body text-content-primary">
            {question}
          </p>
          <div>
            <p className="flex items-center gap-2 font-main-ui-action text-content-primary">
              <BrandMark className="size-5" />
              {assistantName}
            </p>
            <p className="mt-3 font-main-content-body text-content-primary">
              {answer.map((segment) => (
                <span key={segment.citation}>
                  {segment.text} <CitationMarker index={segment.citation} />{" "}
                </span>
              ))}
            </p>
          </div>
          <div>
            <p className="font-secondary-action text-content-muted">{sourcesLabel}</p>
            <ol className="mt-2 space-y-2">
              {citations.map((citation) => (
                <li
                  key={citation.index}
                  className="flex items-start gap-3 rounded-lg border border-border-subtle px-3 py-2.5"
                >
                  <CitationMarker index={citation.index} />
                  <span className="min-w-0">
                    <span className="block truncate font-main-ui-body text-content-primary">
                      {citation.title}
                    </span>
                    <span className="block font-secondary-body text-content-muted">
                      {citation.location}
                    </span>
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </div>
        <p className="flex items-center gap-2 border-t border-border-subtle bg-surface-base px-5 py-3 font-secondary-body text-content-secondary sm:px-6">
          <LockKeyhole aria-hidden="true" className="size-3.5 shrink-0" />
          {accessNote}
        </p>
      </div>
    </figure>
  );
}

export { ProductPreview };
