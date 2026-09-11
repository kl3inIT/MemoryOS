import { BrandMark } from "@/components/brand-mark";
import { illustrationCard } from "@/lib/illustration";
import { cn } from "@/lib/utils";
import { enter } from "@/sections/capabilities/animate-mock";

function SsoMock() {
  return (
    <div className="flex h-full items-center justify-center p-5 sm:p-6">
      <div {...enter("rise", 0)} className={cn(illustrationCard, "w-full max-w-72 p-5")}>
        <p className="flex items-center gap-2 font-main-ui-action text-content-primary">
          <BrandMark className="size-6" />
          Sign in to MemoryOS
        </p>
        <span className="mt-4 flex h-9 items-center justify-center rounded-lg bg-[var(--action-default-primary-surface)] font-main-ui-action text-[var(--action-default-primary-content)]">
          Continue with SSO
        </span>
        <span
          {...enter("growX", 0.6, 0.7)}
          className="mt-3 block h-0.5 origin-left rounded-full bg-accent"
        />
        <div
          {...enter("rise", 1.35)}
          className="mt-4 flex items-center gap-3 rounded-lg bg-surface-canvas p-3"
        >
          <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-accent-surface font-secondary-action text-accent-content">
            PA
          </span>
          <span>
            <span className="block font-main-ui-action text-content-primary">
              Procurement analyst
            </span>
            <span className="block font-secondary-body text-content-muted">Group: Procurement</span>
          </span>
        </div>
      </div>
    </div>
  );
}

export { SsoMock };
