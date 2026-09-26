import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Building2, Check, Info, Link2, Lock, ShieldCheck } from "lucide-react";
import { PersonAvatar } from "@/components/composites/person-avatar";
import { Button } from "@/components/ui/button";
import { NativeSelect } from "@/components/ui/native-select";
import { shareChatPersona } from "@/lib/hey-api/sdk.gen";
import { can } from "@/lib/resource-permissions";
import { FormDialog } from "@/components/composites/form-dialog";
import { personLabel, type Person, type NamedRef } from "@/features/identity/principals";
import type { AgentPermission, Persona } from "@/features/chat/chat-personas-api";
import { PrincipalPicker } from "@/features/identity/principal-picker";
import { AgentTransferDialog } from "./agent-transfer-dialog";

type Access = "INVITED" | "VIEWER" | "EDITOR";
const removeAccess = "__remove__";

/**
 * Sharing laid out like Perplexity and GitBook: an invite field, everyone with access and their role, then
 * organization-wide access. Sharing an agent never grants document access; readers still only search Sources they
 * can read.
 */
export function AgentShareDialog({ agent, onClose }: { agent: Persona; onClose: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [people, setPeople] = useState<{ person: Person; permission: AgentPermission }[]>(
    agent.userShares,
  );
  const [groups, setGroups] = useState<{ group: NamedRef; permission: AgentPermission }[]>(
    agent.groupShares,
  );
  const initialAccess: Access = agent.isPublic ? agent.publicPermission : "INVITED";
  const [access, setAccess] = useState<Access>(initialAccess);
  const [transferring, setTransferring] = useState(false);
  const [copied, setCopied] = useState(false);
  const canPublish = can(agent, "setPublic");
  const dirty =
    access !== initialAccess ||
    JSON.stringify(people.map((row) => [row.person.actorId, row.permission])) !==
      JSON.stringify(agent.userShares.map((row) => [row.person.actorId, row.permission])) ||
    JSON.stringify(groups.map((row) => [row.group.id, row.permission])) !==
      JSON.stringify(agent.groupShares.map((row) => [row.group.id, row.permission]));
  const exclude = new Set([
    ...people.map((row) => row.person.actorId),
    ...groups.map((row) => row.group.id),
    ...(agent.owner.actor ? [agent.owner.actor.actorId] : []),
    ...(agent.owner.group ? [agent.owner.group.id] : []),
  ]);
  const ownerName =
    agent.owner.group?.name ?? (personLabel(agent.owner.actor) || ui("Chưa có chủ sở hữu"));

  const roleSelect = (
    value: AgentPermission,
    label: string,
    onChange: (next: AgentPermission) => void,
    onRemove: () => void,
  ) => (
    <label className="shrink-0">
      <span className="sr-only">{label}</span>
      <NativeSelect
        className="h-8 w-36 rounded-lg border-transparent bg-transparent text-sm hover:bg-surface-sunken"
        value={value}
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
    </label>
  );

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

  if (transferring) return <AgentTransferDialog agent={agent} onClose={onClose} />;

  return (
    <FormDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={ui("Chia sẻ {{v1}}", { v1: agent.name })}
      description={ui(
        "Người được chia sẻ dùng trợ lý với quyền của chính họ: trợ lý chỉ tìm trong những nguồn tài liệu họ được phép đọc.",
      )}
      submitLabel={ui("Lưu chia sẻ")}
      submitDisabled={!dirty}
      onSubmit={async () => {
        await shareChatPersona({
          path: { personaId: agent.id },
          query: { revision: agent.revision },
          body: {
            users: people.map((row) => ({
              actorId: row.person.actorId,
              permission: row.permission,
            })),
            groups: groups.map((row) => ({ groupId: row.group.id, permission: row.permission })),
            isPublic: canPublish ? access !== "INVITED" : undefined,
            publicPermission: canPublish && access !== "INVITED" ? access : undefined,
          },
          signal: AbortSignal.timeout(30000),
        });
        await cache.invalidateQueries({ queryKey: ["chat-personas"] });
      }}
    >
      <div className="flex flex-col gap-5">
        <PrincipalPicker
          exclude={exclude}
          onPick={(principal) =>
            principal.kind === "person"
              ? setPeople([...people, { person: principal.person, permission: "VIEWER" }])
              : setGroups([...groups, { group: principal.group, permission: "VIEWER" }])
          }
        />

        <section aria-labelledby="agent-access-list" className="flex flex-col gap-1">
          <h3 id="agent-access-list" className="font-main-ui-action text-content-primary">
            {ui("Người có quyền truy cập")}
          </h3>
          <ul className="-mx-2 flex max-h-72 flex-col overflow-y-auto">
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
            {people.map((row) => (
              <AccessRow
                key={row.person.actorId}
                avatar={<PersonAvatar name={personLabel(row.person)} seed={row.person.actorId} />}
                title={personLabel(row.person)}
                subtitle={row.person.name ? row.person.email : undefined}
                trailing={roleSelect(
                  row.permission,
                  ui("Quyền của {{v1}}", { v1: personLabel(row.person) }),
                  (permission) =>
                    setPeople(
                      people.map((item) => (item === row ? { ...item, permission } : item)),
                    ),
                  () => setPeople(people.filter((item) => item !== row)),
                )}
              />
            ))}
            {groups.map((row) => (
              <AccessRow
                key={row.group.id}
                avatar={<PersonAvatar name={row.group.name} kind="group" />}
                title={row.group.name}
                subtitle={ui("Group")}
                trailing={roleSelect(
                  row.permission,
                  ui("Quyền của {{v1}}", { v1: row.group.name }),
                  (permission) =>
                    setGroups(
                      groups.map((item) => (item === row ? { ...item, permission } : item)),
                    ),
                  () => setGroups(groups.filter((item) => item !== row)),
                )}
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

        <section aria-labelledby="agent-general-access" className="flex flex-col gap-2">
          <h3 id="agent-general-access" className="font-main-ui-action text-content-primary">
            {ui("Quyền truy cập chung")}
          </h3>
          <div className="flex items-center gap-3">
            <span className="grid size-8 shrink-0 place-items-center rounded-full bg-surface-sunken text-content-secondary">
              {access === "INVITED" ? (
                <Lock aria-hidden="true" className="size-4" />
              ) : (
                <Building2 aria-hidden="true" className="size-4" />
              )}
            </span>
            <div className="min-w-0 flex-1">
              <label>
                <span className="sr-only">{ui("Quyền truy cập chung")}</span>
                <NativeSelect
                  className="-ml-2 h-8 w-auto max-w-full rounded-lg border-transparent bg-transparent font-main-ui-action hover:bg-surface-sunken"
                  value={access}
                  disabled={!canPublish}
                  onChange={(event) => setAccess(event.target.value as Access)}
                >
                  <option value="INVITED">{ui("Chỉ người được mời")}</option>
                  <option value="VIEWER">{ui("Mọi người trong tổ chức có thể xem và chat")}</option>
                  <option value="EDITOR">{ui("Mọi người trong tổ chức có thể sửa")}</option>
                </NativeSelect>
              </label>
              <p className="font-secondary-body text-content-muted">
                {!canPublish
                  ? ui(
                      "Chỉ chủ sở hữu hoặc người quản lý trợ lý mới đổi được quyền truy cập chung.",
                    )
                  : access === "INVITED"
                    ? ui("Chỉ những người và Group ở trên mới thấy trợ lý này.")
                    : ui("Trợ lý hiện trong thư viện của mọi thành viên.")}
              </p>
            </div>
          </div>
        </section>

        {agent.sources.length > 0 && (
          <p className="flex gap-2 rounded-xl bg-status-info-surface p-3 font-secondary-body text-status-info-content">
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            {ui(
              "Trợ lý dùng {{v1}} nguồn tài liệu. Người được chia sẻ sẽ thấy tên các nguồn, nhưng chỉ nhận câu trả lời từ tài liệu họ có quyền đọc.",
              { v1: agent.sources.length },
            )}
          </p>
        )}

        <div>
          <Button type="button" size="sm" prominence="secondary" onClick={() => void copyLink()}>
            {copied ? <Check aria-hidden="true" /> : <Link2 aria-hidden="true" />}
            {copied ? ui("Đã sao chép liên kết") : ui("Sao chép liên kết")}
          </Button>
        </div>
      </div>
    </FormDialog>
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
