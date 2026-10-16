import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState, type ReactNode } from "react";
import { useMutation } from "@tanstack/react-query";
import { Building2, Check, Info, Link2, Lock, ShieldCheck, Star } from "lucide-react";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { Switch } from "@/components/ui/switch";
import {
  setChatPersonaListingMutation,
  shareChatPersonaMutation,
  updateChatPersonaMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { PersonaView } from "@/lib/hey-api/types.gen";
import { can } from "@/lib/resource-permissions";
import { actionErrorText } from "@/lib/action-errors";
import { personLabel, type Person, type NamedRef } from "@/features/identity/principals";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import type { AgentPermission, Persona } from "@/features/chat/chat-personas-api";
import { PrincipalPicker } from "@/features/identity/principal-picker";
import { AgentLabelPicker } from "./agent-editor-fields";
import { labelsRequest } from "./agent-form";
import { AgentTransferDialog } from "./agent-transfer-dialog";
import type { AgentChoices, AgentRevision } from "./use-agent-editor";
import { withRequestTimeout } from "@/lib/api";

type Access = "INVITED" | "VIEWER" | "EDITOR";
type Shares = {
  people: { person: Person; permission: AgentPermission }[];
  groups: { group: NamedRef; permission: AgentPermission }[];
  access: Access;
};
const removeAccess = "__remove__";

const sharesOf = (agent: Persona): Shares => ({
  people: agent.userShares,
  groups: agent.groupShares,
  access: agent.isPublic ? agent.publicPermission : "INVITED",
});

/**
 * The Chia sẻ tab: who can use the assistant (Perplexity and GitBook's invite field, everyone with access, then
 * organization-wide access), its labels, and for agent administrators its place in Khám phá. Every change saves at
 * once; a failed one puts the saved state back. Sharing an agent never grants document access.
 */
export function AgentSharePanel({
  agent,
  choices,
  revision: { revision, applied },
}: {
  agent: Persona;
  choices: AgentChoices;
  revision: AgentRevision;
}) {
  const ui = useAppTranslation();
  const manage = useGlobalCapability("AGENTS_MANAGE");
  const share = useMutation(withRequestTimeout(shareChatPersonaMutation()));
  const update = useMutation(withRequestTimeout(updateChatPersonaMutation()));
  const listing = useMutation(withRequestTimeout(setChatPersonaListingMutation()));
  const [shares, setShares] = useState(() => sharesOf(agent));
  const [error, setError] = useState<string>();
  const [transferring, setTransferring] = useState(false);
  const [copied, setCopied] = useState(false);
  const canShare = can(agent, "share");
  const canPublish = can(agent, "setPublic");
  const editable = can(agent, "edit");
  const busy = share.isPending || update.isPending || listing.isPending;

  async function save(write: () => Promise<PersonaView>, rollback?: () => void) {
    setError(undefined);
    try {
      await applied(await write());
    } catch (cause) {
      rollback?.();
      setError(actionErrorText(cause));
    }
  }

  function changeShares(next: Shares) {
    const previous = shares;
    setShares(next);
    void save(
      () =>
        share.mutateAsync({
          path: { personaId: agent.id },
          query: { revision },
          body: {
            users: next.people.map((row) => ({
              actorId: row.person.actorId,
              permission: row.permission,
            })),
            groups: next.groups.map((row) => ({
              groupId: row.group.id,
              permission: row.permission,
            })),
            isPublic: canPublish ? next.access !== "INVITED" : undefined,
            publicPermission: canPublish && next.access !== "INVITED" ? next.access : undefined,
          },
        }),
      () => setShares(previous),
    );
  }

  const exclude = new Set([
    ...shares.people.map((row) => row.person.actorId),
    ...shares.groups.map((row) => row.group.id),
    ...(agent.owner.actor ? [agent.owner.actor.actorId] : []),
    ...(agent.owner.group ? [agent.owner.group.id] : []),
  ]);
  const ownerName =
    agent.owner.group?.name ?? (personLabel(agent.owner.actor) || ui("Chưa có chủ sở hữu"));

  async function copyLink() {
    const url = new URL(`/agents?agent=${agent.id}`, window.location.origin).toString();
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setCopied(false);
    }
  }

  return (
    <div className="flex flex-col gap-8">
      {error && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <section aria-labelledby="agent-share-people" className="flex flex-col gap-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <SectionHeader id="agent-share-people" title={ui("Người có quyền truy cập")} />
          <Button type="button" size="sm" prominence="secondary" onClick={() => void copyLink()}>
            {copied ? (
              <Check data-icon="inline-start" aria-hidden="true" />
            ) : (
              <Link2 data-icon="inline-start" aria-hidden="true" />
            )}
            {copied ? ui("Đã sao chép liên kết") : ui("Sao chép liên kết")}
          </Button>
        </div>
        {canShare && (
          <PrincipalPicker
            exclude={exclude}
            onPick={(principal) =>
              changeShares(
                principal.kind === "person"
                  ? {
                      ...shares,
                      people: [
                        ...shares.people,
                        { person: principal.person, permission: "VIEWER" },
                      ],
                    }
                  : {
                      ...shares,
                      groups: [...shares.groups, { group: principal.group, permission: "VIEWER" }],
                    },
              )
            }
          />
        )}
        <ul className="-mx-2 flex flex-col">
          <AccessRow
            avatar={
              <PersonAvatar
                name={ownerName}
                seed={agent.owner.actor?.actorId ?? agent.owner.group?.id}
                kind={agent.owner.group ? "group" : "person"}
              />
            }
            title={ownerName}
            subtitle={agent.owner.actor?.email}
            trailing={
              <span className="flex items-center gap-1">
                <span className="px-2 font-secondary-body text-content-muted">
                  {ui("Chủ sở hữu")}
                </span>
                {can(agent, "transfer") && (
                  <Button
                    type="button"
                    size="sm"
                    prominence="tertiary"
                    onClick={() => setTransferring(true)}
                  >
                    {ui("Chuyển quyền")}
                  </Button>
                )}
              </span>
            }
          />
          {shares.people.map((row) => (
            <AccessRow
              key={row.person.actorId}
              avatar={<PersonAvatar name={personLabel(row.person)} seed={row.person.actorId} />}
              title={personLabel(row.person)}
              subtitle={row.person.name ? row.person.email : undefined}
              trailing={
                <RoleSelect
                  value={row.permission}
                  disabled={!canShare || busy}
                  label={ui("Quyền của {{v1}}", { v1: personLabel(row.person) })}
                  onChange={(permission) =>
                    changeShares({
                      ...shares,
                      people: shares.people.map((item) =>
                        item === row ? { ...item, permission } : item,
                      ),
                    })
                  }
                  onRemove={() =>
                    changeShares({
                      ...shares,
                      people: shares.people.filter((item) => item !== row),
                    })
                  }
                />
              }
            />
          ))}
          {shares.groups.map((row) => (
            <AccessRow
              key={row.group.id}
              avatar={<PersonAvatar name={row.group.name} kind="group" />}
              title={row.group.name}
              subtitle={ui("Group")}
              trailing={
                <RoleSelect
                  value={row.permission}
                  disabled={!canShare || busy}
                  label={ui("Quyền của {{v1}}", { v1: row.group.name })}
                  onChange={(permission) =>
                    changeShares({
                      ...shares,
                      groups: shares.groups.map((item) =>
                        item === row ? { ...item, permission } : item,
                      ),
                    })
                  }
                  onRemove={() =>
                    changeShares({
                      ...shares,
                      groups: shares.groups.filter((item) => item !== row),
                    })
                  }
                />
              }
            />
          ))}
          <AccessRow
            avatar={
              <span className="grid size-8 place-items-center rounded-full bg-surface-sunken text-content-secondary">
                <ShieldCheck aria-hidden="true" className="size-4" />
              </span>
            }
            title={ui("Quản trị viên trợ lý")}
            subtitle={ui("Người có quyền Quản lý trợ lý trong tổ chức")}
            trailing={
              <span className="px-2 font-secondary-body text-content-muted">
                {ui("Luôn được sửa")}
              </span>
            }
          />
        </ul>
      </section>

      <GeneralAccess
        access={shares.access}
        canPublish={canPublish && !busy}
        onChange={(access) => changeShares({ ...shares, access })}
      />

      {agent.sources.length > 0 && (
        <Alert variant="info">
          <Info aria-hidden="true" />
          <AlertDescription>
            {ui(
              "Trợ lý dùng {{v1}} nguồn tài liệu. Người được chia sẻ sẽ thấy tên các nguồn, nhưng chỉ nhận câu trả lời từ tài liệu họ có quyền đọc.",
              { v1: agent.sources.length },
            )}
          </AlertDescription>
        </Alert>
      )}

      <section aria-labelledby="agent-share-labels" className="flex flex-col gap-3">
        <SectionHeader id="agent-share-labels" title={ui("Nhãn")} />
        <AgentLabelPicker
          labels={choices.labels.data ?? []}
          value={agent.labels.map((label) => label.id)}
          disabled={!editable || busy}
          onChange={(labelIds) =>
            void save(() =>
              update.mutateAsync({
                path: { personaId: agent.id },
                query: { revision },
                body: labelsRequest(agent, labelIds),
              }),
            )
          }
          onCreate={async (name) => {
            let created;
            try {
              created = await choices.createLabel.mutateAsync({ body: { name } });
            } catch (cause) {
              throw new Error(actionErrorText(cause), { cause });
            }
            const id = created.id;
            if (id)
              await save(() =>
                update.mutateAsync({
                  path: { personaId: agent.id },
                  query: { revision },
                  body: labelsRequest(agent, [...agent.labels.map((label) => label.id), id]),
                }),
              );
          }}
        />
      </section>

      {manage && !agent.builtin && (
        <section aria-labelledby="agent-share-listing" className="flex flex-col gap-3">
          <SectionHeader id="agent-share-listing" title={ui("Khám phá")} />
          <SettingRows>
            <SettingRow
              htmlFor="agent-listed"
              icon={<Building2 />}
              title={ui("Hiện ở Khám phá")}
              control={
                <Switch
                  id="agent-listed"
                  checked={agent.listed}
                  disabled={busy}
                  onCheckedChange={(listed) =>
                    void save(() =>
                      listing.mutateAsync({
                        path: { personaId: agent.id },
                        query: { revision },
                        body: {
                          listed,
                          featured: listed && agent.featured,
                          displayPriority: agent.displayPriority ?? undefined,
                        },
                      }),
                    )
                  }
                />
              }
            />
            <SettingRow
              htmlFor="agent-featured"
              icon={<Star />}
              title={ui("Nổi bật")}
              control={
                <Switch
                  id="agent-featured"
                  checked={agent.featured}
                  disabled={busy || !agent.listed}
                  onCheckedChange={(featured) =>
                    void save(() =>
                      listing.mutateAsync({
                        path: { personaId: agent.id },
                        query: { revision },
                        body: {
                          listed: agent.listed,
                          featured,
                          displayPriority: agent.displayPriority ?? undefined,
                        },
                      }),
                    )
                  }
                />
              }
            />
          </SettingRows>
        </section>
      )}

      {transferring && (
        <AgentTransferDialog
          agent={agent}
          revision={revision}
          onTransferred={applied}
          onClose={() => setTransferring(false)}
        />
      )}
    </div>
  );
}

function AccessRow({
  avatar,
  title,
  subtitle,
  trailing,
}: {
  avatar: ReactNode;
  title: string;
  subtitle?: string | null;
  trailing: ReactNode;
}) {
  return (
    <li className="flex min-h-12 items-center gap-3 rounded-lg px-2 py-1.5">
      {avatar}
      <span className="min-w-0 flex-1">
        <span className="block truncate font-main-ui-body text-content-primary">{title}</span>
        {subtitle && (
          <span className="block truncate font-secondary-body text-content-muted">{subtitle}</span>
        )}
      </span>
      {trailing}
    </li>
  );
}

/** Organization-wide access: invited people only, or everyone as viewers or editors. */
function GeneralAccess({
  access,
  canPublish,
  onChange,
}: {
  access: Access;
  canPublish: boolean;
  onChange: (access: Access) => void;
}) {
  const ui = useAppTranslation();
  return (
    <section aria-labelledby="agent-general-access" className="flex flex-col gap-3">
      <SectionHeader id="agent-general-access" title={ui("Quyền truy cập chung")} />
      <div className="flex items-center gap-3">
        <span className="grid size-8 shrink-0 place-items-center rounded-full bg-surface-sunken text-content-secondary">
          {access === "INVITED" ? (
            <Lock aria-hidden="true" className="size-4" />
          ) : (
            <Building2 aria-hidden="true" className="size-4" />
          )}
        </span>
        <NativeSelect
          size="sm"
          aria-label={ui("Quyền truy cập chung")}
          className="w-auto max-w-full"
          value={access}
          disabled={!canPublish}
          onChange={(event) => onChange(event.target.value as Access)}
        >
          <option value="INVITED">{ui("Chỉ người được mời")}</option>
          <option value="VIEWER">{ui("Mọi người trong tổ chức có thể xem và chat")}</option>
          <option value="EDITOR">{ui("Mọi người trong tổ chức có thể sửa")}</option>
        </NativeSelect>
      </div>
    </section>
  );
}

function RoleSelect({
  value,
  label,
  disabled,
  onChange,
  onRemove,
}: {
  value: AgentPermission;
  label: string;
  disabled: boolean;
  onChange: (next: AgentPermission) => void;
  onRemove: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <NativeSelect
      size="sm"
      aria-label={label}
      className="w-36 shrink-0"
      value={value}
      disabled={disabled}
      onChange={(event) =>
        event.target.value === removeAccess
          ? onRemove()
          : onChange(event.target.value as AgentPermission)
      }
    >
      <option value="VIEWER">{ui("Xem và chat")}</option>
      <option value="EDITOR">{ui("Sửa")}</option>
      <option value={removeAccess}>{ui("Gỡ quyền truy cập")}</option>
    </NativeSelect>
  );
}
