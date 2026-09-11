import { illustrationCard } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

const versions = [
  { version: "v1", note: "Draft by Procurement" },
  { version: "v2", note: "Changes requested by Legal" },
  { version: "v3", note: "Approved for Procurement and Legal" },
];

function GovernanceMock() {
  return (
    <div className="flex h-full items-center justify-center p-5 sm:p-6">
      <div {...enter("rise", 0)} className={cn(illustrationCard, "relative w-full max-w-80 p-4")}>
        <p className="font-main-ui-action text-content-primary">Supplier review agent</p>
        <p className="font-secondary-body text-content-muted">Owner: Procurement team</p>
        <ol className="relative mt-4 space-y-3 pl-5">
          <span
            {...enter("growY", 0.2, 0.8)}
            className="absolute top-1.5 bottom-1.5 left-[3px] w-px origin-top bg-border-default"
          />
          {versions.map((entry, index) => (
            <li
              key={entry.version}
              {...enter("slide", 0.25 + index * 0.3)}
              className="relative font-main-ui-body"
            >
              <span
                className={cn(
                  "absolute top-1.5 -left-5 size-2 rounded-full",
                  index === versions.length - 1 ? "bg-approval-content" : "bg-border-strong",
                )}
              />
              <span className="font-main-ui-action text-content-primary">{entry.version}</span>{" "}
              <span className="text-content-secondary">{entry.note}</span>
            </li>
          ))}
        </ol>
        <span
          {...enter("stamp", 1.4)}
          className="absolute right-4 bottom-4 -rotate-6 rounded-md border-2 border-approval-content bg-surface-raised px-2 py-0.5 font-main-ui-action text-approval-content"
        >
          Approved
        </span>
      </div>
    </div>
  );
}

export { GovernanceMock };
