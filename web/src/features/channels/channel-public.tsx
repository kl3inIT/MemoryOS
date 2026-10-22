import { useState } from "react";
import { useQueries, useQuery } from "@tanstack/react-query";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Switch } from "@/components/ui/switch";
import { personasOptions } from "@/features/chat/chat-personas-api";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatPersonaOptions,
  listChannelGroupsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChannelResponse } from "@/lib/hey-api/types.gen";
import { presentProblem } from "@/lib/problem-presentation";
import type { ChannelAdmin } from "./channel-admin";
import { channelError } from "./channel-errors";
import { providerWords } from "./channel-provider";

/**
 * Whether a bot answers anyone (MEM-298); saved on change. Opening it asks first and names what each of its assistants
 * is given, as Copilot Studio warns before an agent anyone may chat with is published; closing it saves at once.
 */
export function ChannelPublicSwitch({
  channel,
  admin,
}: {
  channel: ChannelResponse;
  admin: ChannelAdmin;
}) {
  const ui = useAppTranslation();
  const [asking, setAsking] = useState(false);
  return (
    <>
      <Switch
        checked={channel.publicAccess}
        disabled={admin.publishing(channel)}
        aria-label={ui("Cho mọi người hỏi {{name}}", { name: channel.name })}
        onCheckedChange={(open) => {
          if (open) setAsking(true);
          // A failure shows on the page, under its header.
          else void admin.setPublic(channel, false).catch(() => undefined);
        }}
      />
      {asking ? (
        <ConfirmDialog
          open
          title={ui("Cho mọi người hỏi {{name}}?", { name: channel.name })}
          description={ui(
            "Ai nhắn bot cũng được trả lời, không cần tài khoản, và đọc được tài liệu của các trợ lý dưới đây.",
          )}
          confirmLabel={ui("Công khai")}
          pendingLabel={ui("Đang lưu…")}
          confirmTone="default"
          onConfirm={async () => {
            await admin.setPublic(channel, true);
          }}
          errorMessage={(failure) =>
            channelError(failure, channel.provider) ?? presentProblem(failure, "mutation").message
          }
          onOpenChange={(next) => {
            if (!next) setAsking(false);
          }}
        >
          <Exposure channel={channel} />
        </ConfirmDialog>
      ) : null}
    </>
  );
}

/** Each assistant the bot answers with, where, and what it is given. */
function Exposure({ channel }: { channel: ChannelResponse }) {
  const ui = useAppTranslation();
  const groups = useQuery(listChannelGroupsOptions({ path: { channelId: channel.id } }));
  // A bot without an assistant of its own answers with the built-in one, which may be given Sources too.
  const builtIn = useQuery(personasOptions()).data?.find((persona) => persona.builtin);
  const answering = new Map<string, { name: string; where: string[] }>();
  const add = (id: string, name: string, where: string) => {
    const known = answering.get(id) ?? { name, where: [] };
    known.where.push(where);
    answering.set(id, known);
  };
  add(
    channel.assistant?.id ?? builtIn?.id ?? "",
    channel.assistant?.name ?? builtIn?.name ?? ui("Trợ lý mặc định"),
    ui("mặc định"),
  );
  for (const group of groups.data ?? []) {
    if (!group.assistant) continue;
    const title =
      group.label ||
      group.name ||
      `${ui(providerWords(channel.provider).group)} ${group.id.slice(-4)}`;
    add(group.assistant.id, group.assistant.name, title);
  }
  const rows = [...answering.entries()];
  const views = useQueries({
    queries: rows.map(([id]) => ({
      ...getChatPersonaOptions({ path: { personaId: id } }),
      enabled: id !== "",
    })),
  });

  return (
    <ul className="divide-y divide-border-subtle rounded-xl border border-border-subtle font-secondary-body">
      {rows.map(([id, row], index) => {
        const view = id === "" ? undefined : views[index]?.data;
        const given = [
          ...(view?.sources ?? []).map((source) => source.name ?? ui("Nguồn ẩn")),
          ...(view?.documentSets ?? []).map((set) => set.name ?? ui("Bộ tài liệu ẩn")),
          ...((view?.fileIds?.length ?? 0) > 0
            ? [ui("{{count}} tệp", { count: view?.fileIds?.length ?? 0 })]
            : []),
        ];
        // MEM-299: the apps a public bot uses through this assistant, read tools only.
        const apps = (view?.publicBotMcpServers ?? []).map((server) => server.name ?? "");
        return (
          <li key={id || "built-in"} className="flex flex-col gap-0.5 px-4 py-3">
            <span className="text-content-primary">
              {row.name}
              <span className="text-content-muted"> · {row.where.join(", ")}</span>
            </span>
            <span className="wrap-anywhere text-content-muted">
              {id !== "" && views[index]?.isPending
                ? ui("Đang tải…")
                : given.length > 0
                  ? given.join(", ")
                  : ui("Không có tài liệu")}
            </span>
            {apps.length > 0 ? (
              <span className="wrap-anywhere text-content-muted">
                {ui("Ứng dụng: {{apps}}", { apps: apps.join(", ") })}
              </span>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
