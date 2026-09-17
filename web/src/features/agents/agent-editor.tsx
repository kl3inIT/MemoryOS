import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ImagePlus, Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  createChatPersona,
  createChatPersonaLabel,
  listAvailableChatModels,
  listChatPersonaLabels,
  listChatPersonaModels,
  updateChatPersona,
} from "@/lib/hey-api/sdk.gen";
import { can } from "@/lib/resource-permissions";
import { cn } from "@/lib/utils";
import { ChatDialog } from "@/features/chat/chat-dialog";
import { chatActionError, chatField } from "@/features/chat/chat-action-utils";
import { ChatFilePicker } from "@/features/chat/chat-file-picker";
import { useMcpConnections } from "@/features/chat/chat-mcp-connections";
import {
  agentLabelSchema,
  agentTools,
  loadPersonaSources,
  type AgentTool,
  type Persona,
} from "@/features/chat/chat-workspace-api";
import { AgentAvatar } from "./agent-avatar";
import { agentIcons } from "./agent-icons";

const avatarTypes = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);

export function AgentEditor({ agent, onClose }: { agent?: Persona; onClose: () => void }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const editable = agent ? can(agent, "edit") : true;
  const [name, setName] = useState(agent?.name ?? "");
  const [description, setDescription] = useState(agent?.description ?? "");
  const [iconName, setIconName] = useState(agent?.iconName ?? "bot");
  const [avatarFileId, setAvatarFileId] = useState<string | null>(null);
  const [keepAvatar, setKeepAvatar] = useState(agent?.hasAvatar ?? false);
  const [avatarError, setAvatarError] = useState<string>();
  const [instructions, setInstructions] = useState(agent?.instructions ?? "");
  const [taskPrompt, setTaskPrompt] = useState(agent?.taskPrompt ?? "");
  const [starters, setStarters] = useState(agent?.starterPrompts.join("\n") ?? "");
  const [sourceIds, setSources] = useState(agent?.sourceIds ?? []);
  const [fileIds, setFileIds] = useState(agent?.fileIds ?? []);
  const [cutoff, setCutoff] = useState(agent?.knowledgeCutoff?.slice(0, 10) ?? "");
  const [tools, setTools] = useState<string[]>(agent?.tools ?? [...agentTools]);
  const [mcpServerIds, setMcpServerIds] = useState(
    agent?.mcpServers.map((server) => server.id) ?? [],
  );
  const [labelIds, setLabelIds] = useState(agent?.labels.map((label) => label.id) ?? []);
  const [newLabel, setNewLabel] = useState("");
  const [labelError, setLabelError] = useState<string>();
  const [model, setModel] = useState(agent?.modelConfigurationId ?? "");
  const [context, setContext] = useState(agent?.contextTokenLimit?.toString() ?? "");
  const [output, setOutput] = useState(agent?.outputTokenLimit?.toString() ?? "");
  const [replaceBase, setReplaceBase] = useState(agent?.replaceBaseSystemPrompt ?? false);
  const [datetimeAware, setDatetimeAware] = useState(agent?.datetimeAware ?? true);
  const sources = useQuery({
    queryKey: ["chat-persona-sources", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonaSources(signal),
  });
  const models = useQuery({
    queryKey: ["chat-persona-models", actorId, authorizationVersion, agent?.id],
    queryFn: async ({ signal }) =>
      agent
        ? (
            await listChatPersonaModels({
              path: { personaId: agent.id },
              signal,
              throwOnError: true,
            })
          ).data
        : (await listAvailableChatModels({ signal, throwOnError: true })).data,
  });
  const labels = useQuery({
    queryKey: ["chat-persona-labels", actorId, authorizationVersion],
    queryFn: async ({ signal }) =>
      agentLabelSchema
        .array()
        .parse((await listChatPersonaLabels({ signal, throwOnError: true })).data),
  });
  const mcp = useMcpConnections();
  const toolNames: Record<AgentTool, { label: string; hint: string }> = {
    search: {
      label: ui("Tìm tài liệu nội bộ"),
      hint: ui("Tìm trong nguồn đã chọn, theo quyền đọc của từng người dùng."),
    },
    web_search: { label: ui("Tìm kiếm Web"), hint: ui("Tìm và đọc trang Web công khai.") },
    image_generation: {
      label: ui("Tạo ảnh"),
      hint: ui("Tạo và chỉnh sửa ảnh khi người dùng yêu cầu."),
    },
  };
  const starterPrompts = starters
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  const starterError =
    starterPrompts.length > 8
      ? ui("Dùng tối đa 8 câu hỏi gợi ý.")
      : starterPrompts.some((prompt) => prompt.length > 1000)
        ? ui("Mỗi câu hỏi gợi ý dài tối đa 1.000 ký tự.")
        : undefined;
  const mcpOptions = [
    ...(mcp.data ?? []).map((server) => ({ id: server.id, name: server.name })),
    ...(agent?.mcpServers ?? []).filter(
      (server) => !mcp.data?.some((item) => item.id === server.id),
    ),
  ];
  const toggle = <T,>(values: T[], value: T, on: boolean) =>
    on
      ? [...values.filter((item) => item !== value), value]
      : values.filter((item) => item !== value);

  async function addLabel() {
    const value = newLabel.trim();
    if (!value) return;
    setLabelError(undefined);
    try {
      const created = agentLabelSchema.parse(
        (
          await createChatPersonaLabel({
            body: { name: value },
            headers: sameOriginMutationHeaders,
            signal: AbortSignal.timeout(30000),
            throwOnError: true,
          })
        ).data,
      );
      setLabelIds([...labelIds, created.id]);
      setNewLabel("");
      await cache.invalidateQueries({ queryKey: ["chat-persona-labels"] });
    } catch (cause) {
      setLabelError(chatActionError(cause));
    }
  }

  return (
    <ChatDialog
      open
      wide
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={agent ? agent.name : ui("Tạo trợ lý")}
      description={ui(
        "Người dùng trợ lý chỉ nhận câu trả lời từ tài liệu họ được phép đọc. Hướng dẫn của trợ lý được ưu tiên hơn hướng dẫn dự án.",
      )}
      submitDisabled={!!starterError}
      onSubmit={
        editable
          ? async () => {
              const body = {
                name,
                description,
                instructions,
                taskPrompt,
                starterPrompts,
                sourceIds,
                tools,
                mcpServerIds: agent?.builtin ? [] : mcpServerIds,
                fileIds: agent?.builtin ? undefined : fileIds,
                modelConfigurationId: model || null,
                contextTokenLimit: context ? Number(context) : null,
                outputTokenLimit: output ? Number(output) : null,
                iconName: avatarFileId || keepAvatar ? undefined : iconName,
                avatarFileId: avatarFileId ?? undefined,
                labelIds,
                replaceBaseSystemPrompt: replaceBase,
                datetimeAware,
                knowledgeCutoff: cutoff ? `${cutoff}T00:00:00Z` : undefined,
              };
              if (agent)
                await updateChatPersona({
                  path: { personaId: agent.id },
                  query: { revision: agent.revision },
                  body,
                  headers: sameOriginMutationHeaders,
                  signal: AbortSignal.timeout(30000),
                  throwOnError: true,
                });
              else
                await createChatPersona({
                  body,
                  headers: sameOriginMutationHeaders,
                  signal: AbortSignal.timeout(30000),
                  throwOnError: true,
                });
              await cache.invalidateQueries({ queryKey: ["chat-personas"] });
              await cache.invalidateQueries({ queryKey: ["chat-persona-pins"] });
              await cache.invalidateQueries({ queryKey: ["chat-models"] });
            }
          : undefined
      }
    >
      {starterError && <p role="alert">{starterError}</p>}
      <fieldset disabled={!editable} className="min-w-0">
        <Tabs defaultValue="general" className="mt-2">
          <TabsList variant="line" className="w-full justify-start overflow-x-auto">
            <TabsTrigger value="general">{ui("Chung")}</TabsTrigger>
            <TabsTrigger value="instructions">{ui("Hướng dẫn")}</TabsTrigger>
            <TabsTrigger value="knowledge">{ui("Tri thức")}</TabsTrigger>
            <TabsTrigger value="tools">{ui("Công cụ")}</TabsTrigger>
            <TabsTrigger value="advanced">{ui("Nâng cao")}</TabsTrigger>
          </TabsList>

          <TabsContent value="general" className="space-y-4 pt-4">
            <label className="block space-y-1">
              <span>{ui("Tên trợ lý")}</span>
              <Input
                required
                maxLength={200}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span>{ui("Mô tả")}</span>
              <textarea
                className={chatField}
                maxLength={2000}
                rows={3}
                value={description}
                placeholder={ui("Trợ lý này giúp gì, dùng cho phòng ban nào")}
                onChange={(e) => setDescription(e.target.value)}
              />
            </label>
            <fieldset className="space-y-2">
              <legend>{ui("Biểu tượng")}</legend>
              <div className="flex flex-wrap items-center gap-2">
                <AgentAvatar
                  agent={{
                    id: agent?.id ?? "new",
                    name,
                    iconName,
                    hasAvatar: keepAvatar && !avatarFileId,
                  }}
                  size="lg"
                />
                <div
                  role="radiogroup"
                  aria-label={ui("Biểu tượng")}
                  className="flex flex-wrap gap-1"
                >
                  {Object.entries(agentIcons).map(([key, Icon]) => (
                    <button
                      key={key}
                      type="button"
                      role="radio"
                      aria-checked={!keepAvatar && !avatarFileId && iconName === key}
                      aria-label={key}
                      className={cn(
                        "grid size-9 place-items-center rounded-lg border border-border-subtle hover:bg-surface-subtle",
                        !keepAvatar &&
                          !avatarFileId &&
                          iconName === key &&
                          "border-action-primary bg-surface-subtle",
                      )}
                      onClick={() => {
                        setIconName(key);
                        setKeepAvatar(false);
                        setAvatarFileId(null);
                      }}
                    >
                      <Icon aria-hidden="true" className="size-4" />
                    </button>
                  ))}
                </div>
                {!agent?.builtin && (
                  <ChatFilePicker
                    selected={avatarFileId ? [avatarFileId] : []}
                    onSelect={(ids, files) => {
                      const image = files.filter((file) => ids.includes(file.id)).at(-1);
                      if (!image) return;
                      if (!avatarTypes.has(image.mediaType) || image.sizeBytes > 2 * 1024 * 1024) {
                        setAvatarError(ui("Dùng ảnh PNG, JPEG, WebP hoặc GIF tối đa 2 MiB."));
                        return;
                      }
                      setAvatarError(undefined);
                      setAvatarFileId(image.id);
                      setKeepAvatar(false);
                    }}
                    trigger={
                      <Button type="button" size="sm" prominence="secondary">
                        <ImagePlus aria-hidden="true" className="size-4" />
                        {avatarFileId ? ui("Đã chọn ảnh") : ui("Dùng ảnh")}
                      </Button>
                    }
                  />
                )}
              </div>
              {avatarError && (
                <p role="alert" className="text-sm text-status-danger-content">
                  {avatarError}
                </p>
              )}
            </fieldset>
            <fieldset className="space-y-2">
              <legend>{ui("Nhãn")}</legend>
              <div className="flex flex-wrap gap-2">
                {labels.data?.map((label) => (
                  <label
                    key={label.id}
                    className="flex items-center gap-2 rounded-lg border border-border-subtle px-2 py-1 text-sm"
                  >
                    <Checkbox
                      checked={labelIds.includes(label.id)}
                      onCheckedChange={(checked) =>
                        setLabelIds(toggle(labelIds, label.id, checked === true))
                      }
                    />
                    {label.name}
                  </label>
                ))}
              </div>
              <div className="flex gap-2">
                <label className="flex-1">
                  <span className="sr-only">{ui("Tên nhãn mới")}</span>
                  <Input
                    maxLength={100}
                    value={newLabel}
                    placeholder={ui("Nhãn mới, ví dụ: Tài chính")}
                    onChange={(e) => setNewLabel(e.target.value)}
                  />
                </label>
                <Button
                  type="button"
                  prominence="secondary"
                  disabled={!newLabel.trim()}
                  onClick={() => void addLabel()}
                >
                  <Plus aria-hidden="true" className="size-4" />
                  {ui("Thêm nhãn")}
                </Button>
              </div>
              {labelError && (
                <p role="alert" className="text-sm text-status-danger-content">
                  {labelError}
                </p>
              )}
            </fieldset>
          </TabsContent>

          <TabsContent value="instructions" className="space-y-4 pt-4">
            <label className="block space-y-1">
              <span>{ui("Hướng dẫn")}</span>
              <textarea
                className={chatField}
                maxLength={32000}
                rows={8}
                value={instructions}
                placeholder={ui(
                  "Vai trò, phạm vi trả lời, cách trích dẫn và định dạng câu trả lời",
                )}
                onChange={(e) => setInstructions(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span>{ui("Nhắc việc mỗi lượt")}</span>
              <textarea
                className={chatField}
                maxLength={32000}
                rows={3}
                value={taskPrompt}
                placeholder={ui(
                  "Lời nhắc ngắn gửi kèm mỗi câu hỏi, ví dụ: luôn nêu tháng và đơn vị của báo cáo",
                )}
                onChange={(e) => setTaskPrompt(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span>{ui("Câu hỏi gợi ý")}</span>
              <textarea
                className={chatField}
                rows={4}
                value={starters}
                onChange={(e) => setStarters(e.target.value)}
              />
              <span className="text-xs text-content-muted">
                {ui("Mỗi dòng một câu. Tối đa 8 câu, mỗi câu 1.000 ký tự.")}
              </span>
            </label>
          </TabsContent>

          <TabsContent value="knowledge" className="space-y-4 pt-4">
            <fieldset className="space-y-2">
              <legend>{ui("Nguồn tài liệu")}</legend>
              <p className="text-xs text-content-muted">
                {ui("Không chọn nguồn: tìm trong tất cả nguồn người dùng được phép đọc.")}
              </p>
              {sources.isError && (
                <p role="alert">
                  {ui("Không tải được nguồn.")}{" "}
                  <Button
                    type="button"
                    prominence="internal"
                    onClick={() => void sources.refetch()}
                  >
                    {ui("Tải lại")}
                  </Button>
                </p>
              )}
              {sources.isPending && <p role="status">{ui("Đang tải nguồn…")}</p>}
              <div className="max-h-52 space-y-2 overflow-y-auto rounded-xl border border-border-subtle p-3">
                {sources.data?.map((source) => (
                  <label key={source.id} className="flex items-center gap-2">
                    <Checkbox
                      checked={sourceIds.includes(source.id)}
                      onCheckedChange={(checked) =>
                        setSources(toggle(sourceIds, source.id, checked === true))
                      }
                    />
                    {source.name}
                  </label>
                ))}
                {sources.isSuccess &&
                  sourceIds
                    .filter((id) => !sources.data.some((source) => source.id === id))
                    .map((id) => (
                      <label key={id} className="flex items-center gap-2 text-sm">
                        <Checkbox
                          checked
                          onCheckedChange={() =>
                            setSources(sourceIds.filter((source) => source !== id))
                          }
                        />
                        {agent?.sources.find((source) => source.id === id)?.name ||
                          ui("Nguồn không còn khả dụng (đang giữ lựa chọn)")}
                      </label>
                    ))}
              </div>
            </fieldset>
            {!agent?.builtin && (
              <fieldset className="space-y-2">
                <legend>{ui("Tệp đính kèm")}</legend>
                <p className="text-xs text-content-muted">
                  {ui("Người dùng trợ lý đọc được các tệp này trong hội thoại.")}
                </p>
                <ChatFilePicker selected={fileIds} onSelect={setFileIds} />
              </fieldset>
            )}
            <label className="block space-y-1">
              <span>{ui("Chỉ dùng tài liệu cập nhật từ ngày")}</span>
              <Input
                type="date"
                value={cutoff}
                onChange={(e) => setCutoff(e.target.value)}
                className="sm:w-56"
              />
              <span className="block text-xs text-content-muted">
                {ui("Bỏ trống để tìm trong mọi tài liệu.")}
              </span>
            </label>
          </TabsContent>

          <TabsContent value="tools" className="space-y-4 pt-4">
            <fieldset className="space-y-3">
              <legend className="sr-only">{ui("Công cụ")}</legend>
              {agentTools.map((tool) => (
                <label
                  key={tool}
                  className="flex items-start justify-between gap-4 rounded-xl border border-border-subtle p-3"
                >
                  <span>
                    <span className="block font-medium">{toolNames[tool].label}</span>
                    <span className="block text-sm text-content-muted">{toolNames[tool].hint}</span>
                  </span>
                  <Switch
                    checked={tools.includes(tool)}
                    onCheckedChange={(checked) => setTools(toggle(tools, tool, checked))}
                    aria-label={toolNames[tool].label}
                  />
                </label>
              ))}
            </fieldset>
            <fieldset className="space-y-2">
              <legend>{ui("Máy chủ MCP")}</legend>
              {agent?.builtin ? (
                <p className="text-sm text-content-muted">
                  {ui("Trợ lý mặc định dùng mọi máy chủ MCP mà người dùng được phép dùng.")}
                </p>
              ) : mcpOptions.length === 0 ? (
                <p className="text-sm text-content-muted">{ui("Chưa có máy chủ MCP nào.")}</p>
              ) : (
                mcpOptions.map((server) => (
                  <label key={server.id} className="flex items-center gap-2">
                    <Checkbox
                      checked={mcpServerIds.includes(server.id)}
                      onCheckedChange={(checked) =>
                        setMcpServerIds(toggle(mcpServerIds, server.id, checked === true))
                      }
                    />
                    {server.name}
                  </label>
                ))
              )}
            </fieldset>
          </TabsContent>

          <TabsContent value="advanced" className="space-y-4 pt-4">
            <label className="block space-y-1">
              <span>{ui("Model mặc định")}</span>
              <Select value={model} onChange={(e) => setModel(e.target.value)}>
                <option value="">{ui("Tự động")}</option>
                {models.data
                  ?.filter((m) => m.id)
                  .map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.displayName || m.modelName}
                    </option>
                  ))}
                {model && !models.data?.some((m) => m.id === model) && (
                  <option value={model}>{ui("Model không còn khả dụng")}</option>
                )}
              </Select>
            </label>
            {models.isError && (
              <p role="alert">
                {ui("Không tải được model.")}{" "}
                <Button type="button" prominence="internal" onClick={() => void models.refetch()}>
                  {ui("Tải lại")}
                </Button>
              </p>
            )}
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="space-y-1">
                <span>{ui("Context window (token)")}</span>
                <Input
                  type="number"
                  min={256}
                  max={2000000}
                  value={context}
                  onChange={(e) => setContext(e.target.value)}
                />
              </label>
              <label className="space-y-1">
                <span>{ui("Max output (token)")}</span>
                <Input
                  type="number"
                  min={1}
                  max={200000}
                  value={output}
                  onChange={(e) => setOutput(e.target.value)}
                />
              </label>
            </div>
            <p className="text-xs text-content-muted">
              {ui("Để trống để dùng giới hạn của model.")}
            </p>
            {!agent?.builtin && (
              <label className="flex items-start justify-between gap-4 rounded-xl border border-border-subtle p-3">
                <span>
                  <span className="block font-medium">
                    {ui("Thay hướng dẫn hệ thống mặc định")}
                  </span>
                  <span className="block text-sm text-content-muted">
                    {ui("Chỉ dùng hướng dẫn của trợ lý, bỏ hướng dẫn chung của MemoryOS.")}
                  </span>
                </span>
                <Switch
                  checked={replaceBase}
                  onCheckedChange={setReplaceBase}
                  aria-label={ui("Thay hướng dẫn hệ thống mặc định")}
                />
              </label>
            )}
            <label className="flex items-start justify-between gap-4 rounded-xl border border-border-subtle p-3">
              <span>
                <span className="block font-medium">{ui("Cho trợ lý biết ngày hiện tại")}</span>
                <span className="block text-sm text-content-muted">
                  {ui("Hữu ích khi câu hỏi nói đến tháng này hoặc quý trước.")}
                </span>
              </span>
              <Switch
                checked={datetimeAware}
                onCheckedChange={setDatetimeAware}
                aria-label={ui("Cho trợ lý biết ngày hiện tại")}
              />
            </label>
          </TabsContent>
        </Tabs>
      </fieldset>
      {keepAvatar && agent?.hasAvatar && editable && (
        <p className="mt-3 flex items-center gap-2 text-xs text-content-muted">
          {ui("Đang dùng ảnh đại diện đã tải lên.")}
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Bỏ ảnh đại diện")}
            onClick={() => setKeepAvatar(false)}
          >
            <X />
          </IconButton>
        </p>
      )}
    </ChatDialog>
  );
}
