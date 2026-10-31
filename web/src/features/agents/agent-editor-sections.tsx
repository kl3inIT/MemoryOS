import { useQuery } from "@tanstack/react-query";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { Plug, TriangleAlert } from "lucide-react";
import { listAssistantChannelsOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  ModelSelectorContent,
  ModelSelectorEffort,
  ModelSelectorEmpty,
  ModelSelectorGroup,
  ModelSelectorItem,
  ModelSelectorList,
  ModelSelectorRoot,
  ModelSelectorSearch,
  ModelSelectorTrigger,
  ModelSelectorValue,
} from "@/components/assistant-ui/elements/model-selector";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Switch } from "@/components/ui/switch";
import { REASONING_EFFORTS } from "@/features/chat/chat-models";
import { ModelLogo } from "@/features/models/model-logo";
import { agentTools, type Persona } from "@/features/chat/chat-personas-api";
import { AgentAvatarStudio } from "./agent-avatar-studio";
import { AgentDocumentPicker } from "./agent-document-picker";
import {
  EditorField,
  EditorSection,
  LoadFailure,
  StarterPromptsField,
  TextareaField,
  TextLineField,
} from "./agent-editor-fields";
import { maxGreeting, maxTitle, type AgentReasoningEffort } from "./agent-form";
import { toolIcons, useToolNames } from "./agent-tools";
import type { AgentChoices, AgentFormApi } from "./use-agent-editor";

const autoModel = "__auto__";
/** The level a selector shows while the agent sets none. */
const systemEffort = "SYSTEM";

const toggle = <T,>(values: T[], value: T, on: boolean) =>
  on
    ? [...values.filter((item) => item !== value), value]
    : values.filter((item) => item !== value);

type SectionProps = {
  form: AgentFormApi;
  agent: Persona;
  choices: AgentChoices;
  editable: boolean;
};

export function IdentitySection({ form, agent, editable }: SectionProps) {
  const ui = useAppTranslation();
  return (
    <EditorSection id="agent-identity" title={ui("Danh tính")}>
      <div className="flex flex-col gap-5 sm:flex-row sm:items-start">
        <form.Subscribe
          selector={(state) => ({
            avatarSeed: state.values.avatarSeed,
            keepAvatar: state.values.keepAvatar,
            avatarFileId: state.values.avatarFileId,
          })}
        >
          {({ avatarSeed, keepAvatar, avatarFileId }) => (
            <AgentAvatarStudio
              agentId={agent.id}
              avatarSeed={avatarSeed}
              savedSeed={agent.avatarSeed || avatarSeed}
              hasAvatar={keepAvatar}
              avatarFileId={avatarFileId}
              allowImage={!agent.builtin}
              disabled={!editable}
              onSeed={(seed) => {
                form.setFieldValue("avatarSeed", seed);
                form.setFieldValue("keepAvatar", false);
                form.setFieldValue("avatarFileId", null);
              }}
              onImage={(fileId) => {
                form.setFieldValue("avatarFileId", fileId);
                form.setFieldValue("keepAvatar", false);
              }}
              onClearImage={() => {
                form.setFieldValue("avatarFileId", null);
                form.setFieldValue("keepAvatar", false);
              }}
            />
          )}
        </form.Subscribe>
        <div className="flex min-w-0 flex-1 flex-col gap-4">
          <form.AppField name="name">
            {(field) => (
              <field.TextField
                label={ui("Tên trợ lý")}
                size="lg"
                maxLength={200}
                placeholder={ui("Ví dụ: OKR/KPI hằng tháng")}
              />
            )}
          </form.AppField>
          <form.AppField name="title">
            {() => (
              <TextLineField
                label={ui("Chức danh")}
                maxLength={maxTitle}
                placeholder={ui("Ví dụ: Chuyên viên phân tích tài chính")}
              />
            )}
          </form.AppField>
          <form.AppField name="description">
            {() => (
              <TextareaField
                label={ui("Mô tả")}
                counter={(value) => `${value.length}/2000`}
                maxLength={2000}
                rows={2}
                placeholder={ui("Trợ lý này giúp gì, dùng cho phòng ban nào")}
              />
            )}
          </form.AppField>
        </div>
      </div>
    </EditorSection>
  );
}

export function InstructionsSection({ form }: SectionProps) {
  const ui = useAppTranslation();
  return (
    <EditorSection id="agent-instructions" title={ui("Hướng dẫn")}>
      <form.AppField name="instructions">
        {() => (
          <TextareaField
            label={ui("Hướng dẫn")}
            counter={(value) => `${value.length.toLocaleString("vi-VN")}/32.000`}
            maxLength={32000}
            rows={12}
            className="min-h-64"
            placeholder={ui("Vai trò, phạm vi trả lời, cách trích dẫn và định dạng câu trả lời")}
          />
        )}
      </form.AppField>
    </EditorSection>
  );
}

export function OpeningSection({ form, editable }: SectionProps) {
  const ui = useAppTranslation();
  return (
    <EditorSection id="agent-opening" title={ui("Mở đầu")}>
      <form.AppField name="greeting">
        {() => (
          <TextareaField
            label={ui("Lời chào")}
            counter={(value) => `${value.length}/${maxGreeting}`}
            maxLength={maxGreeting}
            rows={2}
            placeholder={ui("Ví dụ: Chào bạn, mình giúp bạn tra số liệu doanh thu và chi phí.")}
          />
        )}
      </form.AppField>
      <form.AppField name="starterPrompts">
        {(field) => (
          <StarterPromptsField
            value={field.state.value}
            disabled={!editable}
            onChange={field.handleChange}
          />
        )}
      </form.AppField>
    </EditorSection>
  );
}

/**
 * Searching documents heads the section as its switch; with it off the choices stay but are disabled, and a turn
 * then reads none of them, files included (Onyx "Use Knowledge"). "Chỉ trả lời có căn cứ" is one switch under the list.
 */
/** The public bots that answer with the assistant (MEM-298): whoever messages them uses what it is given. */
function usePublicBots(agent: Persona): string[] {
  const channels = useQuery({
    ...listAssistantChannelsOptions({ path: { personaId: agent.id } }),
    enabled: !agent.builtin,
  });
  return (channels.data ?? []).filter((bot) => bot.publicAccess).map((bot) => bot.name);
}

function PublicWarning({ children }: { children: string }) {
  return (
    <p className="flex items-start gap-2 font-secondary-body text-status-warning-content">
      <TriangleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      {children}
    </p>
  );
}

export function DocumentsSection({ form, agent, choices, editable }: SectionProps) {
  const ui = useAppTranslation();
  const { sources, documentSets } = choices;
  const publicBots = usePublicBots(agent);
  return (
    <form.AppField name="tools">
      {(tools) => {
        const searching = tools.state.value.includes("search");
        return (
          <EditorSection
            id="agent-documents"
            title={ui("Tài liệu")}
            action={
              <Switch
                aria-label={ui("Tìm trong tài liệu nội bộ")}
                checked={searching}
                disabled={!editable}
                onCheckedChange={(checked) =>
                  tools.handleChange(toggle(tools.state.value, "search", checked))
                }
              />
            }
          >
            {publicBots.length > 0 && (
              <PublicWarning>
                {ui("Ai nhắn bot {{bots}} cũng đọc được tài liệu ở đây.", {
                  bots: publicBots.join(", "),
                })}
              </PublicWarning>
            )}
            <fieldset
              disabled={!editable || !searching}
              className="flex min-w-0 flex-col gap-4 disabled:opacity-60"
            >
              <legend className="sr-only">{ui("Tài liệu")}</legend>
              <form.Subscribe
                selector={(state) => ({
                  sourceIds: state.values.sourceIds,
                  documentSetIds: state.values.documentSetIds,
                  fileIds: state.values.fileIds,
                })}
              >
                {(documents) => (
                  <AgentDocumentPicker
                    sources={sources.data ?? []}
                    documentSets={documentSets.data ?? []}
                    known={{ sources: agent.sources, documentSets: agent.documentSets }}
                    value={documents}
                    withFiles={!agent.builtin}
                    // One list loading is enough to start adding; a failed one says so above the rows.
                    pending={sources.isPending && documentSets.isPending}
                    failed={sources.isError || documentSets.isError}
                    unavailable={sources.isError && documentSets.isError}
                    disabled={!editable || !searching}
                    searchesEverything={
                      searching &&
                      documents.sourceIds.length === 0 &&
                      documents.documentSetIds.length === 0
                    }
                    onRetry={() => {
                      void sources.refetch();
                      void documentSets.refetch();
                    }}
                    onChange={(next) => {
                      form.setFieldValue("sourceIds", next.sourceIds);
                      form.setFieldValue("documentSetIds", next.documentSetIds);
                      form.setFieldValue("fileIds", next.fileIds);
                    }}
                  />
                )}
              </form.Subscribe>
              {!agent.builtin && (
                <form.AppField name="grounded">
                  {(field) => (
                    <SettingRows>
                      <SettingRow
                        htmlFor="agent-grounded"
                        title={ui("Chỉ trả lời có căn cứ")}
                        control={
                          <Switch
                            id="agent-grounded"
                            checked={field.state.value}
                            onCheckedChange={field.handleChange}
                          />
                        }
                      />
                    </SettingRows>
                  )}
                </form.AppField>
              )}
            </fieldset>
          </EditorSection>
        );
      }}
    </form.AppField>
  );
}

export function ToolsSection({ form, agent, choices }: SectionProps) {
  const ui = useAppTranslation();
  const toolNames = useToolNames();
  const connected = choices.mcp.data ?? [];
  const publicBots = usePublicBots(agent);
  // MEM-299: a server open through this assistant alone is given on the server's page and is always on here.
  const open = agent.openMcpServers;
  const mcpOptions = [
    ...connected.map((server) => ({ id: server.id, name: server.name })),
    ...agent.mcpServers.filter((server) => !connected.some((item) => item.id === server.id)),
  ].filter((server) => !open.some((item) => item.id === server.id));
  return (
    <EditorSection id="agent-tools" title={ui("Công cụ")}>
      <form.AppField name="tools">
        {(field) => (
          <SettingRows>
            {agentTools
              .filter((tool) => tool !== "search")
              .map((tool) => {
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
                        checked={field.state.value.includes(tool)}
                        onCheckedChange={(checked) =>
                          field.handleChange(toggle(field.state.value, tool, checked))
                        }
                      />
                    }
                  />
                );
              })}
          </SettingRows>
        )}
      </form.AppField>
      {/* Without an MCP server in the organization there is nothing to choose, so the subsection stays out. */}
      {(mcpOptions.length > 0 || open.length > 0) && (
        <EditorField label={ui("Ứng dụng MCP")}>
          {publicBots.length > 0 && agent.publicBotMcpServers.length > 0 && (
            <PublicWarning>
              {ui("Ai nhắn bot {{bots}} cũng dùng được {{apps}}.", {
                bots: publicBots.join(", "),
                apps: agent.publicBotMcpServers.map((server) => server.name).join(", "),
              })}
            </PublicWarning>
          )}
          {agent.builtin ? (
            <p className="font-secondary-body text-content-muted">
              {ui("Trợ lý mặc định dùng mọi máy chủ MCP mà người dùng được phép dùng.")}
            </p>
          ) : (
            <form.AppField name="mcpServerIds">
              {(field) => (
                <SettingRows>
                  {open.map((server) => (
                    <SettingRow
                      key={server.id}
                      htmlFor={`agent-mcp-${server.id}`}
                      icon={<Plug />}
                      title={server.name}
                      description={ui("Luôn bật")}
                      control={<Switch id={`agent-mcp-${server.id}`} checked disabled />}
                    />
                  ))}
                  {mcpOptions.map((server) => (
                    <SettingRow
                      key={server.id}
                      htmlFor={`agent-mcp-${server.id}`}
                      icon={<Plug />}
                      title={server.name}
                      control={
                        <Switch
                          id={`agent-mcp-${server.id}`}
                          checked={field.state.value.includes(server.id)}
                          onCheckedChange={(checked) =>
                            field.handleChange(toggle(field.state.value, server.id, checked))
                          }
                        />
                      }
                    />
                  ))}
                </SettingRows>
              )}
            </form.AppField>
          )}
        </EditorField>
      )}
    </EditorSection>
  );
}

export function ModelSection({ form, choices, editable }: SectionProps) {
  const ui = useAppTranslation();
  const { models } = choices;
  return (
    <EditorSection id="agent-model" title={ui("Model và suy luận")}>
      {models.isError && (
        <LoadFailure onRetry={() => void models.refetch()}>
          {ui("Không tải được model.")}
        </LoadFailure>
      )}
      <SettingRows>
        <form.Subscribe selector={(state) => state.values.reasoningEffort}>
          {(effort) => (
            <form.AppField name="modelConfigurationId">
              {(field) => {
                const selected = field.state.value;
                const efforts = [
                  { id: systemEffort, name: ui("Theo hệ thống") },
                  ...REASONING_EFFORTS.map((level) => ({ id: level.id, name: ui(level.name) })),
                ];
                // Only a reasoning model takes a level; "Tự động" follows the organization's default model.
                const reasons = (id: string) =>
                  (models.data ?? []).some(
                    (model) =>
                      (id === autoModel ? model.isDefault : model.id === id) &&
                      model.capabilities?.reasoning,
                  );
                const options = [
                  {
                    id: autoModel,
                    name: ui("Tự động"),
                    description: ui("Dùng model mặc định của tổ chức"),
                    efforts: reasons(autoModel) ? efforts : undefined,
                  },
                  ...(models.data ?? []).flatMap((model) =>
                    model.id
                      ? [
                          {
                            id: model.id,
                            name: model.displayName || model.modelName || ui("Model"),
                            keywords: [model.modelName ?? "", model.providerName ?? ""],
                            icon: <ModelLogo modelName={model.modelName ?? ""} />,
                            efforts: model.capabilities?.reasoning ? efforts : undefined,
                          },
                        ]
                      : [],
                  ),
                  ...(selected &&
                  models.isSuccess &&
                  !models.data.some((model) => model.id === selected)
                    ? [{ id: selected, name: ui("Model không còn khả dụng"), disabled: true }]
                    : []),
                ];
                return (
                  <SettingRow
                    title={ui("Model mặc định")}
                    control={
                      <ModelSelectorRoot
                        models={options}
                        value={selected || autoModel}
                        onValueChange={(value) => {
                          field.handleChange(value === autoModel ? "" : value);
                          // A level the new model cannot take is dropped, as the API refuses it.
                          if (!reasons(value)) form.setFieldValue("reasoningEffort", "");
                        }}
                        effort={effort || systemEffort}
                        onEffortChange={(value) =>
                          form.setFieldValue(
                            "reasoningEffort",
                            value === systemEffort ? "" : (value as AgentReasoningEffort),
                          )
                        }
                      >
                        <ModelSelectorTrigger
                          aria-label={ui("Model mặc định")}
                          disabled={!editable || models.isPending}
                          className="h-9 max-w-64 min-w-40"
                        >
                          <ModelSelectorValue showEffort={effort !== ""} />
                        </ModelSelectorTrigger>
                        <ModelSelectorContent className="w-80 max-w-[calc(100vw-2rem)]" align="end">
                          <ModelSelectorSearch
                            aria-label={ui("Tìm mô hình")}
                            placeholder={ui("Tìm mô hình…")}
                          />
                          <ModelSelectorList>
                            <ModelSelectorEmpty>{ui("Không tìm thấy mô hình.")}</ModelSelectorEmpty>
                            <ModelSelectorGroup>
                              {options.map((model) => (
                                <ModelSelectorItem key={model.id} model={model} />
                              ))}
                            </ModelSelectorGroup>
                          </ModelSelectorList>
                          <ModelSelectorEffort label={ui("Suy luận")} />
                        </ModelSelectorContent>
                      </ModelSelectorRoot>
                    }
                  />
                );
              }}
            </form.AppField>
          )}
        </form.Subscribe>
      </SettingRows>
    </EditorSection>
  );
}
