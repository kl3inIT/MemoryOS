import { useAppTranslation } from "@/i18n/use-app-translation";
import { KeyRound, LockKeyhole, ShieldCheck } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import type { GroupCapability } from "@/lib/hey-api/types.gen";
import { capabilityCopy } from "./group-capability-copy";

type CapabilityId = GroupCapability["id"];

type GroupPermissionsSectionProps = {
  registry: readonly GroupCapability[];
  selected: ReadonlySet<CapabilityId>;
  editable: boolean;
  loading: boolean;
  error: boolean;
  onRetry: () => void;
  onChange: (capabilities: Set<CapabilityId>) => void;
};

export function GroupPermissionsSection({
  registry,
  selected,
  editable,
  loading,
  error,
  onRetry,
  onChange,
}: GroupPermissionsSectionProps) {
  const ui = useAppTranslation();

  return (
    <section
      aria-labelledby="group-permissions-heading"
      className="border-t border-border-subtle pt-7"
    >
      <div className="flex items-start gap-3">
        <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-subtle text-content-secondary">
          <KeyRound className="size-4" aria-hidden="true" />
        </span>
        <div>
          <h2 id="group-permissions-heading" className="font-heading-h3 text-content-primary">
            {ui("Capabilities")}
          </h2>
          <p className="mt-1 font-main-ui-body text-content-muted">
            {ui("The registry lists only capabilities with working product enforcement.")}
          </p>
        </div>
      </div>

      {loading ? (
        <div
          role="status"
          className="mt-4 rounded-xl border border-border-subtle px-4 py-6 font-main-ui-body text-content-muted"
        >
          {ui("Loading capability registry")}
        </div>
      ) : error ? (
        <div className="mt-4 rounded-xl border border-border-subtle p-4">
          <p role="alert" className="font-main-ui-body text-content-secondary">
            {ui(
              "The capability registry could not be loaded. Existing grants have not been changed.",
            )}
          </p>
          <Button size="sm" prominence="secondary" className="mt-3" onClick={onRetry}>
            {ui("Try again")}
          </Button>
        </div>
      ) : registry.length === 0 ? (
        <div className="mt-4 rounded-xl border border-dashed border-border-default px-4 py-8 text-center font-main-ui-body text-content-muted">
          {ui("No capabilities are available.")}
        </div>
      ) : (
        <div className="mt-4 divide-y divide-border-subtle overflow-hidden rounded-xl border border-border-subtle bg-surface-raised">
          {registry.map((capability) => {
            const copy = capabilityCopy[capability.id];
            const checked = selected.has(capability.id);
            const mutable = editable && capability.editable;
            return (
              <label
                key={capability.id}
                className={`flex items-start gap-3 px-4 py-4 ${mutable ? "cursor-pointer transition-colors hover:bg-surface-subtle has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-inset has-[:focus-visible]:ring-focus-ring/30" : "cursor-default"}`}
              >
                <span className="mt-0.5 grid size-8 shrink-0 place-items-center rounded-lg bg-surface-subtle text-content-secondary">
                  {capability.editable ? (
                    <ShieldCheck className="size-4" aria-hidden="true" />
                  ) : (
                    <LockKeyhole className="size-4" aria-hidden="true" />
                  )}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="font-main-ui-action text-content-primary">
                      {ui(copy?.label ?? "Unknown")}
                    </span>
                    {!capability.editable ? (
                      <Badge variant="outline" className="bg-surface-raised text-content-muted">
                        {ui("Protected")}
                      </Badge>
                    ) : null}
                  </span>
                  <span className="mt-1 block font-secondary-body text-content-muted">
                    {copy ? ui(copy.description) : null}
                  </span>
                  {capability.id.startsWith("SOURCES_") ? (
                    <span className="mt-2 block font-secondary-action text-content-secondary">
                      {ui(
                        "Tenant-wide grant. Source associations below constrain only scoped group-manager authority.",
                      )}
                    </span>
                  ) : null}
                  {capability.implies.length > 0 ? (
                    <span className="mt-2 flex flex-wrap items-center gap-1 font-secondary-body text-content-muted">
                      {ui("Includes")}
                      {capability.implies.map((implied) => (
                        <Badge
                          key={implied}
                          variant="secondary"
                          className="bg-surface-subtle text-content-secondary"
                        >
                          {ui(capabilityCopy[implied]?.label ?? "Unknown")}
                        </Badge>
                      ))}
                    </span>
                  ) : null}
                </span>
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={!mutable}
                  aria-label={ui(checked ? "Remove {{capability}}" : "Grant {{capability}}", {
                    capability: ui(copy?.label ?? "Unknown"),
                  })}
                  className="mt-2 size-4 shrink-0 accent-content-primary outline-none disabled:cursor-not-allowed"
                  onChange={() => {
                    const next = new Set(selected);
                    if (checked) next.delete(capability.id);
                    else next.add(capability.id);
                    onChange(next);
                  }}
                />
              </label>
            );
          })}
        </div>
      )}
    </section>
  );
}
