import { useAppTranslation } from "@/i18n/use-app-translation";
import { capabilityCopy } from "./group-capability-copy";
import {
  Bot,
  ChevronsDownUp,
  ChevronsUpDown,
  Cpu,
  FileSearch,
  FolderCog,
  Plug,
  ScrollText,
  ShieldCheck,
  UserCog,
  Users,
} from "lucide-react";
import type { ComponentProps, ComponentType } from "react";
import { Alert, AlertAction, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { FieldDescription, FieldLabel } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import type { GroupCapability, GroupSummary } from "@/lib/hey-api/types.gen";

type CapabilityId = GroupCapability["id"];

// Stable capability IDs select localized copy and presentation icons.
const ICONS: Partial<Record<CapabilityId, ComponentType<ComponentProps<"svg">>>> = {
  SYSTEM_ADMIN: UserCog,
  SYSTEM_BASIC: FileSearch,
  USERS_MANAGE: UserCog,
  GROUPS_MANAGE: Users,
  SOURCES_MANAGE: FolderCog,
  MODELS_MANAGE: Cpu,
  MCP_MANAGE: Plug,
  AGENTS_CREATE: Bot,
  AGENTS_MANAGE: Bot,
  AUDIT_READ: ScrollText,
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
    <Collapsible defaultOpen asChild>
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
          <CollapsibleTrigger asChild>
            <Button
              prominence="internal"
              size="sm"
              aria-label={ui("Toggle group permissions")}
              className="group shrink-0 px-2"
            >
              <ChevronsDownUp
                data-icon="inline-start"
                className="group-data-[state=closed]:hidden"
                aria-hidden="true"
              />
              <ChevronsUpDown
                data-icon="inline-start"
                className="group-data-[state=open]:hidden"
                aria-hidden="true"
              />
            </Button>
          </CollapsibleTrigger>
        </div>

        <CollapsibleContent className="mt-4">
          {loading ? (
            <p
              role="status"
              className="flex items-center gap-2 py-6 font-main-ui-body text-content-muted"
            >
              <Spinner aria-hidden="true" />
              {ui("Loading capability registry")}
            </p>
          ) : error ? (
            <Alert variant="destructive">
              <AlertDescription>
                {ui(
                  "The capability registry could not be loaded. Existing grants have not been changed.",
                )}
              </AlertDescription>
              <AlertAction>
                <Button size="sm" prominence="secondary" onClick={onRetry}>
                  {ui("Try again")}
                </Button>
              </AlertAction>
            </Alert>
          ) : rows.length === 0 ? (
            <p className="py-6 font-main-ui-body text-content-muted">
              {ui("No capabilities are available.")}
            </p>
          ) : (
            <ul
              aria-label={ui("Group permission grants")}
              className="rounded-2xl border border-border-subtle bg-surface-raised p-4"
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
                        <FieldLabel id={labelId} htmlFor={`group-grant-${capability.id}`}>
                          {ui(copy?.label ?? "Unknown")}
                        </FieldLabel>
                        <FieldDescription id={descriptionId} className="mt-1">
                          {copy ? ui(copy.description) : null}
                        </FieldDescription>
                      </div>
                      <Switch
                        id={`group-grant-${capability.id}`}
                        checked={checked}
                        disabled={!mutable}
                        aria-labelledby={labelId}
                        aria-describedby={descriptionId}
                        className="mt-0.5"
                        onCheckedChange={(enabled) => {
                          if (!mutable) return;
                          const next = new Set(selected);
                          if (enabled) next.add(capability.id);
                          else next.delete(capability.id);
                          onChange(next);
                        }}
                      />
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </CollapsibleContent>
      </section>
    </Collapsible>
  );
}
