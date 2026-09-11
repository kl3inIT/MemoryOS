import { illustrationCard, illustrationChip, illustrationLabel } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

const slots = [
  { label: "Knowledge", value: "Procurement policies" },
  { label: "Instructions", value: "Check the spending limit first" },
  { label: "Tools", value: "Contract lookup" },
];

function AgentsMock() {
  return (
    <div className="flex h-full items-center justify-center p-5 sm:p-6">
      <div {...enter("rise", 0)} className={cn(illustrationCard, "w-full max-w-80 p-4")}>
        <div className="flex items-center justify-between gap-3">
          <p className="font-main-ui-action text-content-primary">Supplier review agent</p>
          <span
            {...enter("pop", 1.5)}
            className={cn(illustrationChip, "bg-citation-surface text-citation-content")}
          >
            Ready
          </span>
        </div>
        <dl className="mt-3 space-y-2">
          {slots.map((slot, index) => (
            <div
              key={slot.label}
              {...enter("fade", 0.2 + index * 0.1)}
              className="rounded-lg border border-dashed border-border-default p-2.5"
            >
              <dt className={illustrationLabel}>{slot.label}</dt>
              <dd
                {...enter("drop", 0.6 + index * 0.25)}
                className="mt-1 w-fit rounded-md bg-surface-canvas px-2 py-1 font-main-ui-body text-content-primary"
              >
                {slot.value}
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  );
}

export { AgentsMock };
