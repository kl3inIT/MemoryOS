import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Info, User, Users, X } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { Select } from "@/components/ui/select";
import { sameOriginMutationHeaders } from "@/lib/api";
import { shareChatPersona } from "@/lib/hey-api/sdk.gen";
import { can } from "@/lib/resource-permissions";
import { ChatDialog } from "@/features/chat/chat-dialog";
import {
  personLabel,
  type AgentPermission,
  type AgentPerson,
  type AgentRef,
  type Persona,
} from "@/features/chat/chat-workspace-api";
import { AgentPrincipalPicker } from "./agent-principal-picker";

type Access = "INVITED" | "VIEWER" | "EDITOR";

/**
 * Dropbox Dash–style sharing: one picker for people and Groups, a role per row and Tenant-wide access. Sharing an
 * agent never grants document access; readers still only search Sources they can read.
 */
export function AgentShareDialog({ agent, onClose }: { agent: Persona; onClose: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [people, setPeople] = useState<{ person: AgentPerson; permission: AgentPermission }[]>(
    agent.userShares,
  );
  const [groups, setGroups] = useState<{ group: AgentRef; permission: AgentPermission }[]>(
    agent.groupShares,
  );
  const [access, setAccess] = useState<Access>(agent.isPublic ? agent.publicPermission : "INVITED");
  const canPublish = can(agent, "setPublic");
  const exclude = new Set([
    ...people.map((row) => row.person.actorId),
    ...groups.map((row) => row.group.id),
    ...(agent.owner.actor ? [agent.owner.actor.actorId] : []),
  ]);
  const permissionSelect = (
    value: AgentPermission,
    onChange: (next: AgentPermission) => void,
    label: string,
  ) => (
    <label className="shrink-0">
      <span className="sr-only">{label}</span>
      <Select
        className="h-8 w-28 text-sm"
        value={value}
        onChange={(event) => onChange(event.target.value as AgentPermission)}
      >
        <option value="VIEWER">{ui("Dùng")}</option>
        <option value="EDITOR">{ui("Sửa")}</option>
      </Select>
    </label>
  );
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={ui("Chia sẻ {{v1}}", { v1: agent.name })}
      description={ui(
        "Người được chia sẻ dùng trợ lý với quyền của chính họ: trợ lý chỉ tìm trong những nguồn tài liệu họ được phép đọc.",
      )}
      submitLabel={ui("Lưu chia sẻ")}
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
          headers: sameOriginMutationHeaders,
          signal: AbortSignal.timeout(30000),
          throwOnError: true,
        });
        await cache.invalidateQueries({ queryKey: ["chat-personas"] });
      }}
    >
      <div className="space-y-4">
        <AgentPrincipalPicker
          exclude={exclude}
          onPick={(principal) =>
            principal.kind === "person"
              ? setPeople([...people, { person: principal.person, permission: "VIEWER" }])
              : setGroups([...groups, { group: principal.group, permission: "VIEWER" }])
          }
        />
        <section aria-labelledby="agent-access-list">
          <h3 id="agent-access-list" className="mb-2 text-sm font-medium">
            {ui("Người có quyền truy cập")}
          </h3>
          <ul className="divide-y divide-border-subtle rounded-xl border border-border-default">
            <li className="flex items-center gap-3 px-3 py-2">
              {agent.owner.group ? (
                <Users aria-hidden="true" className="size-4 text-content-muted" />
              ) : (
                <User aria-hidden="true" className="size-4 text-content-muted" />
              )}
              <span className="min-w-0 flex-1 truncate text-sm">
                {agent.owner.group?.name ??
                  (personLabel(agent.owner.actor) || ui("Chưa có chủ sở hữu"))}
              </span>
              <span className="text-xs text-content-muted">{ui("Chủ sở hữu")}</span>
            </li>
            {people.map((row) => (
              <li key={row.person.actorId} className="flex items-center gap-3 px-3 py-2">
                <User aria-hidden="true" className="size-4 text-content-muted" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm">{personLabel(row.person)}</span>
                  {row.person.name && row.person.email && (
                    <span className="block truncate text-xs text-content-muted">
                      {row.person.email}
                    </span>
                  )}
                </span>
                {permissionSelect(
                  row.permission,
                  (permission) =>
                    setPeople(
                      people.map((item) => (item === row ? { ...item, permission } : item)),
                    ),
                  ui("Quyền của {{v1}}", { v1: personLabel(row.person) }),
                )}
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Gỡ {{v1}}", { v1: personLabel(row.person) })}
                  onClick={() => setPeople(people.filter((item) => item !== row))}
                >
                  <X />
                </IconButton>
              </li>
            ))}
            {groups.map((row) => (
              <li key={row.group.id} className="flex items-center gap-3 px-3 py-2">
                <Users aria-hidden="true" className="size-4 text-content-muted" />
                <span className="min-w-0 flex-1 truncate text-sm">{row.group.name}</span>
                {permissionSelect(
                  row.permission,
                  (permission) =>
                    setGroups(
                      groups.map((item) => (item === row ? { ...item, permission } : item)),
                    ),
                  ui("Quyền của {{v1}}", { v1: row.group.name }),
                )}
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Gỡ {{v1}}", { v1: row.group.name })}
                  onClick={() => setGroups(groups.filter((item) => item !== row))}
                >
                  <X />
                </IconButton>
              </li>
            ))}
          </ul>
        </section>
        <label className="block space-y-1">
          <span className="text-sm font-medium">{ui("Quyền truy cập chung")}</span>
          <Select
            value={access}
            disabled={!canPublish}
            onChange={(event) => setAccess(event.target.value as Access)}
          >
            <option value="INVITED">{ui("Chỉ người được mời")}</option>
            <option value="VIEWER">{ui("Mọi người trong tổ chức có thể dùng")}</option>
            <option value="EDITOR">{ui("Mọi người trong tổ chức có thể sửa")}</option>
          </Select>
          {!canPublish && (
            <span className="block text-xs text-content-muted">
              {ui("Chỉ chủ sở hữu hoặc người quản lý trợ lý mới đổi được quyền truy cập chung.")}
            </span>
          )}
        </label>
        {agent.sources.length > 0 && (
          <p className="flex gap-2 rounded-xl bg-status-info-surface p-3 text-sm text-status-info-content">
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            {ui(
              "Trợ lý dùng {{v1}} nguồn tài liệu. Người được chia sẻ sẽ thấy tên các nguồn, nhưng chỉ nhận câu trả lời từ tài liệu họ có quyền đọc.",
              { v1: agent.sources.length },
            )}
          </p>
        )}
      </div>
    </ChatDialog>
  );
}
