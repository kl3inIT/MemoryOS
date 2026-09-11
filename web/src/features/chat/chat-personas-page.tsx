import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createChatPersona,
  updateChatPersona,
  deleteChatPersona,
  listAvailableChatModels,
  listChatPersonaModels,
} from "@/lib/hey-api/sdk.gen";
import { ChatDialog } from "./chat-dialog";
import { chatField, chatActionError } from "./chat-action-utils";
import { loadPersonas, loadPersonaSources, type Persona } from "./chat-workspace-api";
import { chatSessionsKey, newChatSession } from "./chat-api";

export function ChatPersonasPage() {
  const { actorId, authorizationVersion } = useApplicationSession();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const list = useQuery({
    queryKey: ["chat-personas", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonas(signal),
  });
  const [editor, setEditor] = useState<Persona | "new">();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string>();
  async function start(persona: Persona) {
    if (pending) return;
    setPending(true);
    setError(undefined);
    try {
      const session = await newChatSession(persona.name, AbortSignal.timeout(30000), persona.id);
      await cache.invalidateQueries({ queryKey: chatSessionsKey });
      await navigate({ to: "/chat/$sessionId", params: { sessionId: session.id } });
    } catch (cause) {
      setError(chatActionError(cause));
    } finally {
      setPending(false);
    }
  }
  return (
    <AppShell pageTitle="Trợ lý">
      <div className="mx-auto w-full max-w-5xl overflow-y-auto p-6">
        <div className="mb-6 flex items-start justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold">Trợ lý</h1>
            <p className="mt-2 text-content-secondary">
              Lưu hướng dẫn, câu hỏi gợi ý và nguồn tài liệu cho từng công việc.
            </p>
          </div>
          <Button onClick={() => setEditor("new")}>Tạo trợ lý</Button>
        </div>
        {(error || list.isError) && (
          <p role="alert" className="mb-4">
            {error ?? "Không tải được danh sách trợ lý."}{" "}
            <Button prominence="internal" onClick={() => void list.refetch()}>
              Tải lại
            </Button>
          </p>
        )}
        {list.isPending && <p role="status">Đang tải trợ lý…</p>}
        <div className="grid gap-4 sm:grid-cols-2">
          {list.data?.map((persona) => (
            <article key={persona.id} className="rounded-2xl border border-border-default p-5">
              <h2 className="text-lg font-medium">{persona.name}</h2>
              <p className="mt-1 text-xs text-content-muted">
                {persona.builtin ? "Trợ lý mặc định" : "Chỉ mình tôi"}
              </p>
              <p className="mt-3 whitespace-pre-wrap text-sm text-content-secondary">
                {persona.description || "Chưa có mô tả."}
              </p>
              <div className="mt-5 flex flex-wrap gap-2">
                <Button size="sm" disabled={pending} onClick={() => void start(persona)}>
                  Bắt đầu hội thoại
                </Button>
                <Button size="sm" prominence="secondary" onClick={() => setEditor(persona)}>
                  {persona.editable ? "Chỉnh sửa" : "Xem cấu hình"}
                </Button>
                {!persona.builtin && (
                  <ConfirmDialog
                    pendingLabel="Đang lưu…"
                    title="Xóa trợ lý?"
                    description="Các hội thoại cũ vẫn được giữ. Hãy chọn trợ lý khác để tiếp tục trả lời."
                    confirmLabel="Xóa trợ lý"
                    errorMessage={chatActionError}
                    trigger={
                      <Button size="sm" prominence="internal">
                        Xóa
                      </Button>
                    }
                    onConfirm={async () => {
                      await deleteChatPersona({
                        path: { personaId: persona.id },
                        query: { revision: persona.revision },
                        headers: sameOriginMutationHeaders,
                        throwOnError: true,
                      });
                      await cache.invalidateQueries({ queryKey: ["chat-personas"] });
                    }}
                  />
                )}
              </div>
            </article>
          ))}
        </div>
        {editor && (
          <PersonaEditor
            key={editor === "new" ? "new" : editor.id}
            persona={editor === "new" ? undefined : editor}
            onClose={() => setEditor(undefined)}
          />
        )}
      </div>
    </AppShell>
  );
}

function PersonaEditor({ persona, onClose }: { persona?: Persona; onClose: () => void }) {
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [name, setName] = useState(persona?.name ?? "");
  const [description, setDescription] = useState(persona?.description ?? "");
  const [instructions, setInstructions] = useState(persona?.instructions ?? "");
  const [starters, setStarters] = useState(persona?.starterPrompts.join("\n") ?? "");
  const [sourceIds, setSources] = useState(persona?.sourceIds ?? []);
  const [searchEnabled, setSearch] = useState(persona?.searchEnabled ?? true);
  const [model, setModel] = useState(persona?.modelConfigurationId ?? "");
  const [context, setContext] = useState(persona?.contextTokenLimit?.toString() ?? "");
  const [output, setOutput] = useState(persona?.outputTokenLimit?.toString() ?? "");
  const sources = useQuery({
    queryKey: ["chat-persona-sources", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonaSources(signal),
  });
  const models = useQuery({
    queryKey: ["chat-persona-models", actorId, authorizationVersion, persona?.id],
    queryFn: async ({ signal }) =>
      persona
        ? (
            await listChatPersonaModels({
              path: { personaId: persona.id },
              signal,
              throwOnError: true,
            })
          ).data
        : (await listAvailableChatModels({ signal, throwOnError: true })).data,
  });
  const editable = persona?.editable ?? true;
  return (
    <ChatDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={persona ? persona.name : "Tạo trợ lý"}
      description="Trợ lý riêng chỉ bạn sử dụng. Hướng dẫn của trợ lý riêng được ưu tiên hơn hướng dẫn dự án."
      onSubmit={
        editable
          ? async () => {
              const body = {
                name,
                description,
                instructions,
                starterPrompts: starters
                  .split("\n")
                  .map((s) => s.trim())
                  .filter(Boolean),
                sourceIds,
                searchEnabled,
                modelConfigurationId: model || null,
                contextTokenLimit: context ? Number(context) : null,
                outputTokenLimit: output ? Number(output) : null,
              };
              if (persona)
                await updateChatPersona({
                  path: { personaId: persona.id },
                  query: { revision: persona.revision },
                  body,
                  headers: sameOriginMutationHeaders,
                  throwOnError: true,
                });
              else
                await createChatPersona({
                  body,
                  headers: sameOriginMutationHeaders,
                  throwOnError: true,
                });
              await cache.invalidateQueries({ queryKey: ["chat-personas"] });
              await cache.invalidateQueries({ queryKey: ["chat-models"] });
            }
          : undefined
      }
    >
      <fieldset disabled={!editable} className="space-y-4">
        <label className="block space-y-1">
          <span>Tên trợ lý</span>
          <Input required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="block space-y-1">
          <span>Mô tả</span>
          <textarea
            className={chatField}
            maxLength={2000}
            rows={2}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </label>
        <label className="block space-y-1">
          <span>Hướng dẫn</span>
          <textarea
            className={chatField}
            maxLength={32000}
            rows={5}
            value={instructions}
            onChange={(e) => setInstructions(e.target.value)}
          />
        </label>
        <label className="block space-y-1">
          <span>Câu hỏi gợi ý</span>
          <textarea
            className={chatField}
            rows={3}
            value={starters}
            onChange={(e) => setStarters(e.target.value)}
          />
          <span className="text-xs text-content-muted">
            Mỗi dòng một câu. Tối đa 8 câu, mỗi câu 1.000 ký tự.
          </span>
        </label>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={searchEnabled}
            onChange={(e) => setSearch(e.target.checked)}
          />
          Tìm kiếm tài liệu
        </label>
        <fieldset className="space-y-2">
          <legend>Nguồn tài liệu</legend>
          <p className="text-xs text-content-muted">
            Không chọn nguồn: tìm trong tất cả nguồn bạn được phép đọc.
          </p>
          {sources.isError && (
            <p role="alert">
              Không tải được nguồn.{" "}
              <Button type="button" prominence="internal" onClick={() => void sources.refetch()}>
                Tải lại
              </Button>
            </p>
          )}
          {sources.isPending && <p role="status">Đang tải nguồn…</p>}
          <div className="max-h-40 space-y-2 overflow-y-auto">
            {sources.data?.map((source) => (
              <label key={source.id} className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={sourceIds.includes(source.id)}
                  onChange={(e) =>
                    setSources(
                      e.target.checked
                        ? [...sourceIds, source.id]
                        : sourceIds.filter((id) => id !== source.id),
                    )
                  }
                />
                {source.name}
              </label>
            ))}
          </div>
          {sources.isSuccess &&
            sourceIds
              .filter((id) => !sources.data.some((s) => s.id === id))
              .map((id) => (
                <label key={id} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked
                    onChange={() => setSources(sourceIds.filter((source) => source !== id))}
                  />
                  Nguồn không còn khả dụng (đang giữ lựa chọn)
                </label>
              ))}
        </fieldset>
        <label className="block space-y-1">
          <span>Model mặc định</span>
          <Select value={model} onChange={(e) => setModel(e.target.value)}>
            <option value="">Tự động</option>
            {models.data
              ?.filter((m) => m.id)
              .map((m) => (
                <option key={m.id} value={m.id}>
                  {m.displayName || m.modelName}
                </option>
              ))}
            {model && !models.data?.some((m) => m.id === model) && (
              <option value={model}>Model không còn khả dụng</option>
            )}
          </Select>
        </label>
        {models.isError && (
          <p role="alert">
            Không tải được model.{" "}
            <Button type="button" prominence="internal" onClick={() => void models.refetch()}>
              Tải lại
            </Button>
          </p>
        )}
        <details>
          <summary className="cursor-pointer">Giới hạn nâng cao</summary>
          <p className="my-2 text-xs text-content-muted">Để trống để dùng giới hạn của model.</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <label>
              Ngữ cảnh (token)
              <Input
                type="number"
                min={256}
                max={2000000}
                value={context}
                onChange={(e) => setContext(e.target.value)}
              />
            </label>
            <label>
              Câu trả lời (token)
              <Input
                type="number"
                min={1}
                max={200000}
                value={output}
                onChange={(e) => setOutput(e.target.value)}
              />
            </label>
          </div>
        </details>
      </fieldset>
    </ChatDialog>
  );
}
