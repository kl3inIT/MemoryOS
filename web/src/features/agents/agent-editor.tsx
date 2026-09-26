import { useAppTranslation } from "@/i18n/use-app-translation";
import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useBlocker, useNavigate } from "@tanstack/react-router";
import { z } from "zod";
import {
  ArrowUp,
  CalendarClock,
  ChevronRight,
  FileSearch,
  Globe,
  ImagePlus,
  Paperclip,
  Plug,
  SquareTerminal,
  WifiOff,
} from "lucide-react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import { BrandLoader } from "@/components/brand-loader";
import { EmptyState } from "@/components/composites/empty-state";
import {
  ModelSelectorContent,
  ModelSelectorEmpty,
  ModelSelectorGroup,
  ModelSelectorItem,
  ModelSelectorList,
  ModelSelectorRoot,
  ModelSelectorSearch,
  ModelSelectorTrigger,
} from "@/components/assistant-ui/elements/model-selector";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { useApplicationSession } from "@/features/identity/application-session-context";
import {
  createChatPersona,
  createChatPersonaLabel,
  getChatPersona,
  listAvailableChatModels,
  listChatPersonaLabels,
  listChatPersonaModels,
  updateChatPersona,
} from "@/lib/hey-api/sdk.gen";
import { can } from "@/lib/resource-permissions";
import { cn } from "@/lib/utils";
import { actionErrorText, formField } from "@/lib/action-errors";
import { ChatFilePicker } from "@/features/library/file-picker";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import { ModelLogo } from "@/features/models/model-logo";
import {
  agentLabelSchema,
  agentTools,
  loadPersonaSources,
  personaSchema,
  type AgentTool,
  type Persona,
} from "@/features/chat/chat-personas-api";
import { loadDocumentSets } from "@/features/document-sets/document-sets-api";
import { AgentAvatar } from "./agent-avatar";
import {
  AgentIconPicker,
  AgentLabelPicker,
  AgentSourcePicker,
  EditorSection,
  Field,
  StarterPromptsField,
  maxStarterPrompts,
} from "./agent-editor-fields";

const toolIcons = {
  search: FileSearch,
  web_search: Globe,
  image_generation: ImagePlus,
  code_interpreter: SquareTerminal,
};
const autoModel = "__auto__";

const formSchema = z.object({
  name: z.string(),
  description: z.string(),
  iconName: z.string(),
  avatarFileId: z.string().nullable(),
  keepAvatar: z.boolean(),
  labelIds: z.array(z.string()),
  instructions: z.string(),
  taskPrompt: z.string(),
  starters: z.array(z.string()),
  sourceIds: z.array(z.string()),
  documentSetIds: z.array(z.string()),
  fileIds: z.array(z.string()),
  cutoff: z.string(),
  tools: z.array(z.string()),
  mcpServerIds: z.array(z.string()),
  model: z.string(),
  context: z.string(),
  output: z.string(),
  replaceBase: z.boolean(),
});
type AgentForm = z.infer<typeof formSchema>;

function initialForm(agent?: Persona): AgentForm {
  return {
    name: agent?.name ?? "",
    description: agent?.description ?? "",
    iconName: agent?.iconName ?? "bot",
    avatarFileId: null,
    keepAvatar: agent?.hasAvatar ?? false,
    labelIds: agent?.labels.map((label) => label.id) ?? [],
    instructions: agent?.instructions ?? "",
    taskPrompt: agent?.taskPrompt ?? "",
    starters: agent?.starterPrompts ?? [],
    sourceIds: agent?.sourceIds ?? [],
    documentSetIds: agent?.documentSetIds ?? [],
    fileIds: agent?.fileIds ?? [],
    cutoff: agent?.knowledgeCutoff?.slice(0, 10) ?? "",
    tools: agent?.tools ?? [...agentTools],
    mcpServerIds: agent?.mcpServers.map((server) => server.id) ?? [],
    model: agent?.modelConfigurationId ?? "",
    context: agent?.contextTokenLimit?.toString() ?? "",
    output: agent?.outputTokenLimit?.toString() ?? "",
    replaceBase: agent?.replaceBaseSystemPrompt ?? false,
  };
}

const draftKey = (actorId: string) => `memoryos.agent-draft.${actorId}`;

function readDraft(actorId: string) {
  try {
    const raw = window.localStorage.getItem(draftKey(actorId));
    const parsed = raw ? formSchema.safeParse(JSON.parse(raw)) : undefined;
    return parsed?.success ? parsed.data : undefined;
  } catch {
    return undefined;
  }
}

function writeDraft(actorId: string, form: AgentForm | undefined) {
  try {
    if (form) window.localStorage.setItem(draftKey(actorId), JSON.stringify(form));
    else window.localStorage.removeItem(draftKey(actorId));
  } catch {
    // Drafts are a convenience; storage may be unavailable.
  }
}

/** `/agents/create` and `/agents/$agentId/edit`: loads the agent, then renders the editor. */
export function AgentEditorPage({ agentId }: { agentId?: string }) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const agent = useQuery({
    queryKey: ["chat-personas", "detail", actorId, authorizationVersion, agentId],
    enabled: !!agentId,
    queryFn: async ({ signal }) =>
      personaSchema.parse((await getChatPersona({ path: { personaId: agentId! }, signal })).data),
  });
  const title = agentId ? (agent.data?.name ?? ui("Sửa trợ lý")) : ui("Tạo trợ lý");
  return (
    <>
      <AppShellHeader title={title} />
      {agentId && agent.isPending ? (
        <div role="status" className="flex justify-center px-(--page-gutter) pt-16">
          <BrandLoader label={ui("Đang tải trợ lý…")} />
        </div>
      ) : agentId && agent.isError ? (
        <EmptyState
          role="alert"
          className="px-(--page-gutter) pt-10"
          icon={<WifiOff />}
          title={ui("Không mở được trợ lý")}
          detail={actionErrorText(agent.error)}
          action={
            <Button size="sm" prominence="secondary" onClick={() => void agent.refetch()}>
              {ui("Tải lại")}
            </Button>
          }
        />
      ) : (
        <AgentEditor key={agent.data?.revision ?? "new"} agent={agent.data} />
      )}
    </>
  );
}

function AgentEditor({ agent }: { agent?: Persona }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const { actorId, authorizationVersion } = useApplicationSession();
  const editable = agent ? can(agent, "edit") : true;
  const [initial] = useState(() => initialForm(agent));
  const [restored] = useState(() => (agent ? undefined : readDraft(actorId)));
  const [form, setForm] = useState<AgentForm>(restored ?? initial);
  const [draftNotice, setDraftNotice] = useState(!!restored);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const saved = useRef(false);
  const set = (patch: Partial<AgentForm>) => setForm((current) => ({ ...current, ...patch }));
  const dirty = JSON.stringify(form) !== JSON.stringify(initial);

  // A new agent keeps a draft in this browser until it is saved or discarded.
  useEffect(() => {
    if (!agent) writeDraft(actorId, dirty ? form : undefined);
  }, [actorId, agent, dirty, form]);

  const blocker = useBlocker({
    shouldBlockFn: () => !!agent && dirty && !saved.current,
    enableBeforeUnload: () => !!agent && dirty && !saved.current,
    withResolver: true,
  });

  const sources = useQuery({
    queryKey: ["chat-persona-sources", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonaSources(signal),
  });
  const documentSets = useQuery({
    queryKey: ["document-sets", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadDocumentSets(signal),
  });
  const models = useQuery({
    queryKey: ["chat-persona-models", actorId, authorizationVersion, agent?.id],
    queryFn: async ({ signal }) =>
      agent
        ? (
            await listChatPersonaModels({
              path: { personaId: agent.id },
              signal,
            })
          ).data
        : (await listAvailableChatModels({ signal })).data,
  });
  const labels = useQuery({
    queryKey: ["chat-persona-labels", actorId, authorizationVersion],
    queryFn: async ({ signal }) =>
      agentLabelSchema.array().parse((await listChatPersonaLabels({ signal })).data),
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
    code_interpreter: {
      label: ui("Chạy Python"),
      hint: ui(
        "Tính toán, xử lý tệp và vẽ biểu đồ bằng Python khi quản trị viên bật Code Interpreter.",
      ),
    },
  };
  const mcpOptions = [
    ...(mcp.data ?? []).map((server) => ({ id: server.id, name: server.name })),
    ...(agent?.mcpServers ?? []).filter(
      (server) => !mcp.data?.some((item) => item.id === server.id),
    ),
  ];
  const modelOptions = [
    { id: autoModel, name: ui("Tự động"), description: ui("Dùng model mặc định của tổ chức") },
    ...(models.data ?? []).flatMap((model) =>
      model.id
        ? [
            {
              id: model.id,
              name: model.displayName || model.modelName || ui("Model"),
              keywords: [model.modelName ?? "", model.providerName ?? ""],
              icon: <ModelLogo modelName={model.modelName ?? ""} />,
            },
          ]
        : [],
    ),
    ...(form.model && models.isSuccess && !models.data.some((model) => model.id === form.model)
      ? [{ id: form.model, name: ui("Model không còn khả dụng"), disabled: true }]
      : []),
  ];
  const toggle = <T,>(values: T[], value: T, on: boolean) =>
    on
      ? [...values.filter((item) => item !== value), value]
      : values.filter((item) => item !== value);
  const starterPrompts = form.starters.map((prompt) => prompt.trim()).filter(Boolean);
  const canSave =
    editable && !saving && form.name.trim() !== "" && starterPrompts.length <= maxStarterPrompts;

  async function createLabel(name: string) {
    try {
      const created = agentLabelSchema.parse(
        (
          await createChatPersonaLabel({
            body: { name },
            signal: AbortSignal.timeout(30000),
          })
        ).data,
      );
      setForm((current) => ({ ...current, labelIds: [...current.labelIds, created.id] }));
      await cache.invalidateQueries({ queryKey: ["chat-persona-labels"] });
    } catch (cause) {
      throw new Error(actionErrorText(cause));
    }
  }

  async function save() {
    if (!canSave) return;
    setSaving(true);
    setError(undefined);
    const body = {
      name: form.name.trim(),
      description: form.description,
      instructions: form.instructions,
      taskPrompt: form.taskPrompt,
      starterPrompts,
      sourceIds: form.sourceIds,
      documentSetIds: form.documentSetIds,
      tools: form.tools,
      mcpServerIds: agent?.builtin ? [] : form.mcpServerIds,
      fileIds: agent?.builtin ? undefined : form.fileIds,
      modelConfigurationId: form.model || null,
      contextTokenLimit: form.context ? Number(form.context) : null,
      outputTokenLimit: form.output ? Number(form.output) : null,
      iconName: form.avatarFileId || form.keepAvatar ? undefined : form.iconName,
      avatarFileId: form.avatarFileId ?? undefined,
      labelIds: form.labelIds,
      replaceBaseSystemPrompt: form.replaceBase,
      knowledgeCutoff: form.cutoff ? `${form.cutoff}T00:00:00Z` : undefined,
    };
    try {
      if (agent)
        await updateChatPersona({
          path: { personaId: agent.id },
          query: { revision: agent.revision },
          body,
          signal: AbortSignal.timeout(30000),
        });
      else
        await createChatPersona({
          body,
          signal: AbortSignal.timeout(30000),
        });
      saved.current = true;
      if (!agent) writeDraft(actorId, undefined);
      await Promise.all([
        cache.invalidateQueries({ queryKey: ["chat-personas"] }),
        cache.invalidateQueries({ queryKey: ["chat-persona-pins"] }),
        cache.invalidateQueries({ queryKey: ["chat-models"] }),
      ]);
      await navigate({ to: "/agents" });
    } catch (cause) {
      setError(actionErrorText(cause));
      setSaving(false);
    }
  }

  return (
    <form
      className="min-h-full"
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      <div className="sticky top-0 z-20 border-b border-border-subtle bg-surface-base/90 backdrop-blur-sm">
        <div className="mx-auto flex h-14 w-full max-w-(--page-width-wide) items-center gap-3 px-(--page-gutter)">
          <nav aria-label={ui("Đường dẫn")} className="flex min-w-0 flex-1 items-center gap-1.5">
            <Link
              to="/agents"
              className="shrink-0 font-main-ui-body text-content-muted outline-none hover:text-content-primary focus-visible:underline"
            >
              {ui("Trợ lý")}
            </Link>
            <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-content-faint" />
            <span className="truncate font-main-ui-action text-content-primary" aria-current="page">
              {agent ? agent.name : ui("Tạo trợ lý")}
            </span>
          </nav>
          <span role="status" className="hidden font-secondary-body text-content-muted sm:inline">
            {!editable
              ? ui("Chỉ xem")
              : !agent && dirty
                ? ui("Đã lưu nháp trên trình duyệt")
                : agent && dirty
                  ? ui("Có thay đổi chưa lưu")
                  : ""}
          </span>
          <Button asChild prominence="secondary">
            <Link to="/agents">{editable ? ui("Hủy") : ui("Đóng")}</Link>
          </Button>
          {editable && (
            <Button type="submit" disabled={!canSave || !dirty} pending={saving}>
              {agent ? ui("Lưu") : ui("Tạo trợ lý")}
            </Button>
          )}
        </div>
      </div>

      <div className="mx-auto flex w-full max-w-(--page-width-wide) gap-10 px-(--page-gutter) pt-8 pb-24">
        <fieldset disabled={!editable} className="min-w-0 flex-1 lg:max-w-3xl">
          <legend className="sr-only">{agent ? agent.name : ui("Tạo trợ lý")}</legend>
          {error && (
            <p
              role="alert"
              className="mb-6 rounded-xl border border-status-danger-content/30 bg-status-danger-surface px-4 py-3 font-main-ui-body text-status-danger-content"
            >
              {error}
            </p>
          )}
          {draftNotice && (
            <div className="mb-6 flex flex-wrap items-center gap-3 rounded-xl bg-surface-sunken px-4 py-3 font-main-ui-body text-content-secondary">
              <span className="flex-1">{ui("Đã khôi phục bản nháp bạn đang tạo dở.")}</span>
              <Button
                type="button"
                size="sm"
                prominence="tertiary"
                onClick={() => {
                  writeDraft(actorId, undefined);
                  setForm(initial);
                  setDraftNotice(false);
                }}
              >
                {ui("Bỏ bản nháp")}
              </Button>
            </div>
          )}

          <EditorSection
            id="agent-general"
            title={ui("Thông tin chung")}
            description={ui("Tên, mô tả và nhãn giúp đồng nghiệp tìm đúng trợ lý trong thư viện.")}
          >
            <div className="flex flex-col gap-5 sm:flex-row sm:items-start">
              <AgentIconPicker
                agentId={agent?.id ?? "new"}
                name={form.name}
                iconName={form.iconName}
                hasAvatar={form.keepAvatar}
                avatarFileId={form.avatarFileId}
                allowImage={!agent?.builtin}
                disabled={!editable}
                onIcon={(iconName) => set({ iconName, keepAvatar: false, avatarFileId: null })}
                onImage={(avatarFileId) => set({ avatarFileId, keepAvatar: false })}
                onClearImage={() => set({ avatarFileId: null, keepAvatar: false })}
              />
              <div className="flex min-w-0 flex-1 flex-col gap-4">
                <Field label={ui("Tên trợ lý")} htmlFor="agent-name">
                  <Input
                    id="agent-name"
                    required
                    size="lg"
                    maxLength={200}
                    value={form.name}
                    placeholder={ui("Ví dụ: OKR/KPI hằng tháng")}
                    onChange={(event) => set({ name: event.target.value })}
                  />
                </Field>
                <Field
                  label={ui("Mô tả")}
                  htmlFor="agent-description"
                  counter={`${form.description.length}/2000`}
                >
                  <textarea
                    id="agent-description"
                    className={formField}
                    maxLength={2000}
                    rows={2}
                    value={form.description}
                    placeholder={ui("Trợ lý này giúp gì, dùng cho phòng ban nào")}
                    onChange={(event) => set({ description: event.target.value })}
                  />
                </Field>
              </div>
            </div>
            <Field label={ui("Nhãn")}>
              <AgentLabelPicker
                labels={labels.data ?? []}
                value={form.labelIds}
                disabled={!editable}
                onChange={(labelIds) => set({ labelIds })}
                onCreate={createLabel}
              />
            </Field>
          </EditorSection>

          <EditorSection
            id="agent-instructions"
            title={ui("Hướng dẫn")}
            description={ui(
              "Cách trợ lý trả lời. Hướng dẫn của trợ lý được ưu tiên hơn hướng dẫn dự án.",
            )}
          >
            <Field
              label={ui("Hướng dẫn")}
              htmlFor="agent-instructions-text"
              counter={`${form.instructions.length.toLocaleString("vi-VN")}/32.000`}
            >
              <textarea
                id="agent-instructions-text"
                className={cn(formField, "min-h-48")}
                maxLength={32000}
                rows={9}
                value={form.instructions}
                placeholder={ui(
                  "Vai trò, phạm vi trả lời, cách trích dẫn và định dạng câu trả lời",
                )}
                onChange={(event) => set({ instructions: event.target.value })}
              />
            </Field>
            <Field
              label={ui("Nhắc việc mỗi lượt")}
              htmlFor="agent-task-prompt"
              hint={ui("Gửi kèm mỗi câu hỏi, dùng cho quy tắc ngắn mà trợ lý hay quên.")}
            >
              <textarea
                id="agent-task-prompt"
                className={formField}
                maxLength={32000}
                rows={2}
                value={form.taskPrompt}
                placeholder={ui("Ví dụ: luôn nêu tháng và đơn vị của báo cáo")}
                onChange={(event) => set({ taskPrompt: event.target.value })}
              />
            </Field>
            <StarterPromptsField
              value={form.starters}
              disabled={!editable}
              onChange={(starters) => set({ starters })}
            />
          </EditorSection>

          <EditorSection
            id="agent-knowledge"
            title={ui("Tri thức")}
            description={ui("Người dùng chỉ nhận câu trả lời từ tài liệu họ được phép đọc.")}
          >
            <Field label={ui("Nguồn tài liệu")}>
              <AgentSourcePicker
                options={sources.data ?? []}
                known={agent?.sources ?? []}
                value={form.sourceIds}
                pending={sources.isPending}
                failed={sources.isError}
                disabled={!editable}
                onRetry={() => void sources.refetch()}
                onChange={(sourceIds) => set({ sourceIds })}
              />
            </Field>
            <Field label={ui("Bộ tài liệu")}>
              <AgentSourcePicker
                kind="document-set"
                options={documentSets.data ?? []}
                known={agent?.documentSets ?? []}
                value={form.documentSetIds}
                pending={documentSets.isPending}
                failed={documentSets.isError}
                disabled={!editable}
                onRetry={() => void documentSets.refetch()}
                onChange={(documentSetIds) => set({ documentSetIds })}
              />
            </Field>
            <SettingRows>
              {!agent?.builtin && (
                <SettingRow
                  icon={<Paperclip />}
                  title={ui("Tệp đính kèm")}
                  description={
                    form.fileIds.length === 0
                      ? ui("Người dùng trợ lý đọc được các tệp này trong hội thoại.")
                      : ui("{{v1}} tệp đã chọn", { v1: form.fileIds.length })
                  }
                  control={
                    editable && (
                      <ChatFilePicker
                        selected={form.fileIds}
                        onSelect={(fileIds) => set({ fileIds })}
                        trigger={
                          <Button type="button" size="sm" prominence="secondary">
                            {ui("Chọn tệp")}
                          </Button>
                        }
                      />
                    )
                  }
                />
              )}
              <SettingRow
                icon={<CalendarClock />}
                htmlFor="agent-cutoff"
                title={ui("Chỉ dùng tài liệu cập nhật từ ngày")}
                description={ui("Bỏ trống để tìm trong mọi tài liệu.")}
                control={
                  <Input
                    id="agent-cutoff"
                    type="date"
                    className="w-40"
                    value={form.cutoff}
                    onChange={(event) => set({ cutoff: event.target.value })}
                  />
                }
              />
            </SettingRows>
          </EditorSection>

          <EditorSection
            id="agent-tools"
            title={ui("Công cụ")}
            description={ui("Những việc trợ lý được làm ngoài trả lời.")}
          >
            <SettingRows>
              {agentTools.map((tool) => {
                const Icon = toolIcons[tool];
                return (
                  <SettingRow
                    key={tool}
                    htmlFor={`agent-tool-${tool}`}
                    icon={<Icon />}
                    title={toolNames[tool].label}
                    description={toolNames[tool].hint}
                    control={
                      <Switch
                        id={`agent-tool-${tool}`}
                        checked={form.tools.includes(tool)}
                        onCheckedChange={(checked) =>
                          set({ tools: toggle(form.tools, tool, checked) })
                        }
                      />
                    }
                  />
                );
              })}
            </SettingRows>
            <Field label={ui("Máy chủ MCP")}>
              {agent?.builtin ? (
                <p className="font-secondary-body text-content-muted">
                  {ui("Trợ lý mặc định dùng mọi máy chủ MCP mà người dùng được phép dùng.")}
                </p>
              ) : mcpOptions.length === 0 ? (
                <div className="flex items-center gap-3 rounded-xl border border-dashed border-border-default px-4 py-3">
                  <span
                    aria-hidden="true"
                    className="grid size-9 shrink-0 place-items-center text-content-muted"
                  >
                    <Plug className="size-4.5" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="font-main-ui-action text-content-primary">
                      {ui("Chưa có máy chủ MCP nào.")}
                    </p>
                    <p className="font-secondary-body text-content-muted">
                      {ui(
                        "Quản trị viên kết nối máy chủ MCP để trợ lý dùng thêm công cụ như Jira, Google Sheets.",
                      )}
                    </p>
                  </div>
                </div>
              ) : (
                <SettingRows>
                  {mcpOptions.map((server) => (
                    <SettingRow
                      key={server.id}
                      htmlFor={`agent-mcp-${server.id}`}
                      icon={<Plug />}
                      title={server.name}
                      control={
                        <Switch
                          id={`agent-mcp-${server.id}`}
                          checked={form.mcpServerIds.includes(server.id)}
                          onCheckedChange={(checked) =>
                            set({ mcpServerIds: toggle(form.mcpServerIds, server.id, checked) })
                          }
                        />
                      }
                    />
                  ))}
                </SettingRows>
              )}
            </Field>
          </EditorSection>

          <EditorSection
            id="agent-advanced"
            title={ui("Nâng cao")}
            description={ui("Model và giới hạn token. Để trống để dùng giới hạn của model.")}
          >
            {models.isError && (
              <p role="alert" className="font-secondary-body text-status-danger-content">
                {ui("Không tải được model.")}{" "}
                <Button
                  type="button"
                  size="sm"
                  prominence="tertiary"
                  onClick={() => void models.refetch()}
                >
                  {ui("Tải lại")}
                </Button>
              </p>
            )}
            <SettingRows>
              <SettingRow
                title={ui("Model mặc định")}
                description={ui("Người dùng vẫn đổi được model khi chat.")}
                control={
                  <ModelSelectorRoot
                    models={modelOptions}
                    value={form.model || autoModel}
                    onValueChange={(value) => set({ model: value === autoModel ? "" : value })}
                  >
                    <ModelSelectorTrigger
                      aria-label={ui("Model mặc định")}
                      disabled={!editable || models.isPending}
                      className="h-9 max-w-64 min-w-40 rounded-lg border-border-subtle bg-surface-raised"
                    />
                    <ModelSelectorContent className="w-80 max-w-[calc(100vw-2rem)]" align="end">
                      <ModelSelectorSearch
                        aria-label={ui("Tìm mô hình")}
                        placeholder={ui("Tìm mô hình…")}
                      />
                      <ModelSelectorList>
                        <ModelSelectorEmpty>{ui("Không tìm thấy mô hình.")}</ModelSelectorEmpty>
                        <ModelSelectorGroup>
                          {modelOptions.map((model) => (
                            <ModelSelectorItem key={model.id} model={model} />
                          ))}
                        </ModelSelectorGroup>
                      </ModelSelectorList>
                    </ModelSelectorContent>
                  </ModelSelectorRoot>
                }
              />
              <SettingRow
                htmlFor="agent-context"
                title={ui("Context window (token)")}
                description={ui("Lượng hội thoại và tài liệu tối đa gửi cho model.")}
                control={
                  <Input
                    id="agent-context"
                    type="number"
                    min={256}
                    max={2000000}
                    className="w-32 text-right tabular-nums"
                    value={form.context}
                    placeholder={ui("Theo model")}
                    onChange={(event) => set({ context: event.target.value })}
                  />
                }
              />
              <SettingRow
                htmlFor="agent-output"
                title={ui("Max output (token)")}
                description={ui("Độ dài tối đa của một câu trả lời.")}
                control={
                  <Input
                    id="agent-output"
                    type="number"
                    min={1}
                    max={200000}
                    className="w-32 text-right tabular-nums"
                    value={form.output}
                    placeholder={ui("Theo model")}
                    onChange={(event) => set({ output: event.target.value })}
                  />
                }
              />
            </SettingRows>
            {!agent?.builtin && (
              <SettingRows>
                <SettingRow
                  htmlFor="agent-replace-base"
                  title={ui("Thay hướng dẫn hệ thống mặc định")}
                  description={ui(
                    "Chỉ dùng hướng dẫn của trợ lý, bỏ hướng dẫn chung của MemoryOS.",
                  )}
                  control={
                    <Switch
                      id="agent-replace-base"
                      checked={form.replaceBase}
                      onCheckedChange={(replaceBase) => set({ replaceBase })}
                    />
                  }
                />
              </SettingRows>
            )}
          </EditorSection>
        </fieldset>

        <aside aria-label={ui("Xem trước")} className="hidden w-[22rem] shrink-0 lg:block">
          <AgentPreview form={form} agentId={agent?.id ?? "new"} toolNames={toolNames} />
        </aside>
      </div>

      <ConfirmDialog
        open={blocker.status === "blocked"}
        onOpenChange={(open) => {
          if (!open && blocker.status === "blocked") blocker.reset();
        }}
        title={ui("Bỏ thay đổi chưa lưu?")}
        description={ui("Các thay đổi của trợ lý này sẽ mất nếu bạn rời trang.")}
        confirmLabel={ui("Rời trang")}
        pendingLabel={ui("Đang rời trang…")}
        onConfirm={async () => blocker.proceed?.()}
      />
    </form>
  );
}

/** What colleagues will see when they open the agent; sending needs a saved agent. */
function AgentPreview({
  form,
  agentId,
  toolNames,
}: {
  form: AgentForm;
  agentId: string;
  toolNames: Record<AgentTool, { label: string }>;
}) {
  const ui = useAppTranslation();
  const starters = form.starters
    .map((prompt) => prompt.trim())
    .filter(Boolean)
    .slice(0, 4);
  const name = form.name.trim() || ui("Trợ lý chưa đặt tên");
  return (
    <div className="sticky top-22 flex flex-col gap-2">
      <p className="px-1 font-secondary-action text-content-muted">{ui("Xem trước")}</p>
      <div className="flex min-h-[28rem] flex-col rounded-2xl border border-border-subtle surface-card p-5">
        <div className="flex flex-1 flex-col items-center justify-center gap-3 px-2 py-6 text-center">
          <AgentAvatar
            agent={{
              id: agentId,
              name,
              iconName: form.iconName,
              hasAvatar: form.keepAvatar && !form.avatarFileId,
            }}
            size="lg"
          />
          <div>
            <p className="font-heading-h3 text-content-primary">{name}</p>
            <p className="mt-1 line-clamp-3 font-secondary-body text-content-muted">
              {form.description || ui("Mô tả sẽ hiện ở đây.")}
            </p>
          </div>
          <div className="flex flex-wrap justify-center gap-1.5">
            {form.tools.map((tool) => {
              const Icon = toolIcons[tool as AgentTool];
              return Icon ? (
                <span
                  key={tool}
                  className="inline-flex h-6 items-center gap-1 rounded-full bg-surface-sunken px-2 font-secondary-body text-content-secondary"
                >
                  <Icon aria-hidden="true" className="size-3" />
                  {toolNames[tool as AgentTool].label}
                </span>
              ) : null;
            })}
          </div>
        </div>
        {starters.length > 0 && (
          <ul className="mb-3 flex flex-col gap-1.5">
            {starters.map((prompt, index) => (
              <li
                key={index}
                className="truncate rounded-xl border border-border-subtle bg-surface-raised px-3 py-2 text-left font-secondary-body text-content-secondary"
              >
                {prompt}
              </li>
            ))}
          </ul>
        )}
        <div className="flex items-center gap-2 rounded-xl border border-border-default bg-surface-raised py-2 pr-2 pl-3">
          <span className="flex-1 truncate font-main-ui-body text-content-faint">
            {ui("Hỏi {{v1}}…", { v1: name })}
          </span>
          <span
            aria-hidden="true"
            className="grid size-7 place-items-center rounded-lg bg-surface-strong text-surface-raised"
          >
            <ArrowUp className="size-4" />
          </span>
        </div>
      </div>
      <p className="px-1 font-secondary-body text-content-muted">
        {ui("Lưu trợ lý rồi bấm Bắt đầu chat để thử.")}
      </p>
    </div>
  );
}
