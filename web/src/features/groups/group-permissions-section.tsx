import { useAppTranslation } from "@/i18n/use-app-translation";
import { capabilityCopy } from "./group-capability-copy";
import {
  ChevronsDownUp,
  ChevronsUpDown,
  Cpu,
  FileSearch,
  FolderCog,
  ShieldCheck,
} from "lucide-react";
import { Collapsible, Switch } from "radix-ui";
import type { ComponentProps, ComponentType } from "react";
import { OnyxUserManageIcon, OnyxUsersIcon } from "@/components/icons/identity-icons";
import { Button } from "@/components/ui/button";
import type { GroupCapability, GroupSummary } from "@/lib/hey-api/types.gen";

type CapabilityId = GroupCapability["id"];

// Stable capability IDs select localized copy and presentation icons.
const ICONS: Partial<Record<CapabilityId, ComponentType<ComponentProps<"svg">>>> = {
  SYSTEM_ADMIN: OnyxUserManageIcon,
  SYSTEM_BASIC: FileSearch,
  USERS_MANAGE: OnyxUserManageIcon,
  GROUPS_MANAGE: OnyxUsersIcon,
  SOURCES_MANAGE: FolderCog,
  MODELS_MANAGE: Cpu,
};

function permissionSection(id: CapabilityId) {
  if (id === "SYSTEM_ADMIN" || id === "SYSTEM_BASIC") return "access";
  if (id.startsWith("SOURCES_")) return "sources";
  return "administration";
}

type GroupPermissionsSectionProps = {
  registry: readonly GroupCapability[];
  selected: ReadonlySet<CapabilityId>;
  systemKey: GroupSummary["systemKey"];
  editable: boolean;
  loading: boolean;
  error: boolean;
  onRetry: () => void;
  onChange: (capabilities: Set<CapabilityId>) => void;
};

export function GroupPermissionsSection({
  registry,
  selected,
  systemKey,
  editable,
  loading,
  error,
  onRetry,
  onChange,
}: GroupPermissionsSectionProps) {
  const ui = useAppTranslation();
  const systemGroup = systemKey === "ADMIN" || systemKey === "BASIC";
  const systemGrant =
    systemKey === "ADMIN" ? "SYSTEM_ADMIN" : systemKey === "BASIC" ? "SYSTEM_BASIC" : null;
  const rows = registry.filter((capability) =>
    systemGroup ? capability.id === systemGrant : capability.editable,
  );

  return (
    <Collapsible.Root defaultOpen asChild>
      <section
        aria-labelledby="group-permissions-heading"
        className="mt-8 border-t border-border-subtle pt-7"
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 id="group-permissions-heading" className="font-heading-h3 text-content-primary">
              {ui("Group Permissions")}
            </h2>
          </div>
          <Collapsible.Trigger asChild>
            <Button
              prominence="internal"
              size="sm"
              aria-label={ui("Toggle group permissions")}
              className="group shrink-0 px-2 text-content-secondary"
            >
              <ChevronsDownUp
                className="size-4 group-data-[state=closed]:hidden"
                aria-hidden="true"
              />
              <ChevronsUpDown
                className="size-4 group-data-[state=open]:hidden"
                aria-hidden="true"
              />
            </Button>
          </Collapsible.Trigger>
        </div>

        <Collapsible.Content>
          {loading ? (
            <div role="status" className="mt-4 py-6 font-main-ui-body text-content-muted">
              {ui("Loading capability registry")}
            </div>
          ) : error ? (
            <div className="mt-4 rounded-2xl border border-border-subtle p-4">
              <p role="alert" className="font-main-ui-body text-content-secondary">
                {ui(
                  "The capability registry could not be loaded. Existing grants have not been changed.",
                )}
              </p>
              <Button size="sm" prominence="secondary" className="mt-3" onClick={onRetry}>
                {ui("Try again")}
              </Button>
            </div>
          ) : rows.length === 0 ? (
            <p className="mt-4 py-6 font-main-ui-body text-content-muted">
              {ui("No capabilities are available.")}
            </p>
          ) : (
            <ul
              aria-label={ui("Group permission grants")}
              className="mt-4 rounded-2xl border border-border-subtle bg-surface-raised p-4"
            >
              {rows.map((capability, index) => {
                const copy = capabilityCopy[capability.id];
                const Icon = ICONS[capability.id] ?? ShieldCheck;
                const checked = selected.has(capability.id);
                const mutable = !systemGroup && editable && capability.editable;
                const previous = rows[index - 1];
                const sectionStart =
                  previous && permissionSection(previous.id) !== permissionSection(capability.id);
                const labelId = `group-grant-${capability.id}-label`;
                const descriptionId = `group-grant-${capability.id}-description`;

                return (
                  <li
                    key={capability.id}
                    className={sectionStart ? "mt-3 border-t border-border-subtle pt-3" : undefined}
                  >
                    <div className="flex items-start gap-3 px-1 py-3">
                      <Icon
                        className="mt-0.5 size-4 shrink-0 text-content-secondary"
                        aria-hidden="true"
                      />
                      <div className="min-w-0 flex-1">
                        <label
                          id={labelId}
                          htmlFor={`group-grant-${capability.id}`}
                          className={`font-main-ui-action text-content-primary ${mutable ? "cursor-pointer" : "cursor-default"}`}
                        >
                          {ui(copy?.label ?? "Unknown")}
                        </label>
                        <p
                          id={descriptionId}
                          className="mt-1 font-secondary-body text-content-muted"
                        >
                          {copy ? ui(copy.description) : null}
                        </p>
                      </div>
                      <Switch.Root
                        id={`group-grant-${capability.id}`}
                        type="button"
                        checked={checked}
                        disabled={!mutable}
                        aria-labelledby={labelId}
                        aria-describedby={descriptionId}
                        className="mt-0.5 inline-flex h-5 w-9 shrink-0 items-center rounded-full border border-border-default bg-surface-sunken p-0.5 outline-none transition-colors data-[state=checked]:border-content-primary data-[state=checked]:bg-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40 disabled:cursor-not-allowed disabled:opacity-60"
                        onCheckedChange={(enabled) => {
                          if (!mutable) return;
                          const next = new Set(selected);
                          if (enabled) next.add(capability.id);
                          else next.delete(capability.id);
                          onChange(next);
                        }}
                      >
                        <Switch.Thumb className="pointer-events-none block size-3.5 rounded-full bg-content-primary shadow-xs transition-transform data-[state=checked]:translate-x-4 data-[state=checked]:bg-surface-base" />
                      </Switch.Root>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </Collapsible.Content>
      </section>
    </Collapsible.Root>
  );
}
