import { useAppTranslation } from "@/i18n/use-app-translation";
import { Paperclip, Plug } from "lucide-react";
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
import { EmptyState } from "@/components/composites/empty-state";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { REASONING_EFFORTS } from "@/features/chat/chat-models";
import { ChatFilePicker } from "@/features/library/file-picker";
import { ModelLogo } from "@/features/models/model-logo";
import { agentTools, type Persona } from "@/features/chat/chat-personas-api";
import { actionErrorText } from "@/lib/action-errors";
import { AgentAvatarStudio } from "./agent-avatar-studio";
import {
  AgentLabelPicker,
  EditorField,
  EditorSection,
  LoadFailure,
  StarterPromptsField,
  TextareaField,
  TextLineField,
} from "./agent-editor-fields";
import { maxGreeting, maxTitle, type AgentReasoningEffort } from "./agent-form";
import { AgentSourcePicker } from "./agent-source-picker";
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
  agent?: Persona;
  choices: AgentChoices;
  editable: boolean;
};

export function GeneralSection({ form, agent, choices, editable }: SectionProps) {
  const ui = useAppTranslation();
  return (
    <EditorSection
      id="agent-general"
      title={ui("Thông tin chung")}
      description={ui(
        "Tên, chức danh, mô tả và nhãn giúp đồng nghiệp tìm đúng trợ lý trong thư viện.",
      )}
    >
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
              agentId={agent?.id ?? "new"}
              avatarSeed={avatarSeed}
              savedSeed={agent?.avatarSeed ?? form.options.defaultValues?.avatarSeed ?? avatarSeed}
              hasAvatar={keepAvatar}
              avatarFileId={avatarFileId}
              allowImage={!agent?.builtin}
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
      <form.AppField name="labelIds">
        {(field) => (
          <EditorField label={ui("Nhãn")}>
            <AgentLabelPicker
              labels={choices.labels.data ?? []}
              value={field.state.value}
              disabled={!editable}
              onChange={field.handleChange}
              onCreate={async (name) => {
                try {
                  const created = await choices.createLabel.mutateAsync({ body: { name } });
                  if (created.id) field.handleChange([...field.state.value, created.id]);
                } catch (cause) {
                  throw new Error(actionErrorText(cause), { cause });
                }
              }}
            />
          </EditorField>
        )}
      </form.AppField>
    </EditorSection>
  );
}

export function InstructionsSection({ form, editable }: SectionProps) {
  const ui = useAppTranslation();
  return (
    <EditorSection
      id="agent-instructions"
      title={ui("Hướng dẫn")}
      description={ui(
        "Cách trợ lý trả lời. Hướng dẫn của trợ lý được ưu tiên hơn hướng dẫn dự án.",
      )}
    >
      <form.AppField name="instructions">
        {() => (
          <TextareaField
            label={ui("Hướng dẫn")}
            counter={(value) => `${value.length.toLocaleString("vi-VN")}/32.000`}
            maxLength={32000}
            rows={9}
            className="min-h-48"
            placeholder={ui("Vai trò, phạm vi trả lời, cách trích dẫn và định dạng câu trả lời")}
          />
        )}
      </form.AppField>
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

export function KnowledgeSection({ form, agent, choices, editable }: SectionProps) {
  const ui = useAppTranslation();
  const { sources, documentSets } = choices;
  return (
    <EditorSection
      id="agent-knowledge"
      title={ui("Tri thức")}
      description={ui("Người dùng chỉ nhận câu trả lời từ tài liệu họ được phép đọc.")}
    >
      <form.AppField name="sourceIds">
        {(field) => (
          <EditorField label={ui("Nguồn tài liệu")}>
            <AgentSourcePicker
              options={sources.data ?? []}
              known={agent?.sources ?? []}
              value={field.state.value}
              pending={sources.isPending}
              failed={sources.isError}
              disabled={!editable}
              onRetry={() => void sources.refetch()}
              onChange={field.handleChange}
            />
          </EditorField>
        )}
      </form.AppField>
      <form.AppField name="documentSetIds">
        {(field) => (
          <EditorField label={ui("Bộ tài liệu")}>
            <AgentSourcePicker
              kind="document-set"
              options={documentSets.data ?? []}
              known={agent?.documentSets ?? []}
              value={field.state.value}
              pending={documentSets.isPending}
              failed={documentSets.isError}
              disabled={!editable}
              onRetry={() => void documentSets.refetch()}
              onChange={field.handleChange}
            />
          </EditorField>
        )}
      </form.AppField>
      <SettingRows>
        {!agent?.builtin && (
          <form.AppField name="fileIds">
            {(field) => (
              <SettingRow
                icon={<Paperclip />}
                title={ui("Tệp đính kèm")}
                description={
                  field.state.value.length === 0
                    ? ui("Người dùng trợ lý đọc được các tệp này trong hội thoại.")
                    : ui("{{v1}} tệp đã chọn", { v1: field.state.value.length })
                }
                control={
                  editable && (
                    <ChatFilePicker
                      selected={field.state.value}
                      onSelect={(fileIds) => field.handleChange(fileIds)}
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
          </form.AppField>
        )}
      </SettingRows>
    </EditorSection>
  );
}

export function ToolsSection({ form, agent, choices }: SectionProps) {
  const ui = useAppTranslation();
  const toolNames = useToolNames();
  const connected = choices.mcp.data ?? [];
  const mcpOptions = [
    ...connected.map((server) => ({ id: server.id, name: server.name })),
    ...(agent?.mcpServers ?? []).filter(
      (server) => !connected.some((item) => item.id === server.id),
    ),
  ];
  return (
    <EditorSection
      id="agent-tools"
      title={ui("Công cụ")}
      description={ui("Những việc trợ lý được làm ngoài trả lời.")}
    >
      <form.AppField name="tools">
        {(field) => (
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
      <EditorField label={ui("Ứng dụng MCP")}>
        {agent?.builtin ? (
          <p className="font-secondary-body text-content-muted">
            {ui("Trợ lý mặc định dùng mọi máy chủ MCP mà người dùng được phép dùng.")}
          </p>
        ) : mcpOptions.length === 0 ? (
          <EmptyState
            icon={<Plug />}
            title={ui("Chưa có máy chủ MCP nào.")}
            detail={ui(
              "Quản trị viên kết nối máy chủ MCP để trợ lý dùng thêm công cụ như Jira, Google Sheets.",
            )}
          />
        ) : (
          <form.AppField name="mcpServerIds">
            {(field) => (
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
    </EditorSection>
  );
}

export function AdvancedSection({ form, agent, choices, editable }: SectionProps) {
  const ui = useAppTranslation();
  const { models } = choices;
  return (
    <EditorSection
      id="agent-advanced"
      title={ui("Nâng cao")}
      description={ui("Model và mức suy luận của trợ lý.")}
    >
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
                  { id: systemEffort, name: ui("Mặc định") },
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
                    description={ui("Người dùng vẫn đổi được model khi chat.")}
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
      {!agent?.builtin && (
        <SettingRows>
          <form.AppField name="grounded">
            {(field) => (
              <SettingRow
                htmlFor="agent-grounded"
                title={ui("Chỉ trả lời từ tài liệu của tổ chức")}
                control={
                  <Switch
                    id="agent-grounded"
                    checked={field.state.value}
                    onCheckedChange={field.handleChange}
                  />
                }
              />
            )}
          </form.AppField>
        </SettingRows>
      )}
    </EditorSection>
  );
}
