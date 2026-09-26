import { useAppTranslation } from "@/i18n/use-app-translation";
import { CalendarClock, Paperclip, Plug } from "lucide-react";
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
import { EmptyState } from "@/components/composites/empty-state";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { ChatFilePicker } from "@/features/library/file-picker";
import { ModelLogo } from "@/features/models/model-logo";
import { agentTools, type Persona } from "@/features/chat/chat-personas-api";
import { actionErrorText } from "@/lib/action-errors";
import {
  AgentIconPicker,
  AgentLabelPicker,
  EditorField,
  EditorSection,
  LoadFailure,
  StarterPromptsField,
  TextareaField,
} from "./agent-editor-fields";
import { AgentSourcePicker } from "./agent-source-picker";
import { toolIcons, useToolNames } from "./agent-tools";
import type { AgentChoices, AgentFormApi } from "./use-agent-editor";

const autoModel = "__auto__";

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
      description={ui("Tên, mô tả và nhãn giúp đồng nghiệp tìm đúng trợ lý trong thư viện.")}
    >
      <div className="flex flex-col gap-5 sm:flex-row sm:items-start">
        <form.Subscribe
          selector={(state) => ({
            name: state.values.name,
            iconName: state.values.iconName,
            keepAvatar: state.values.keepAvatar,
            avatarFileId: state.values.avatarFileId,
          })}
        >
          {({ name, iconName, keepAvatar, avatarFileId }) => (
            <AgentIconPicker
              agentId={agent?.id ?? "new"}
              name={name}
              iconName={iconName}
              hasAvatar={keepAvatar}
              avatarFileId={avatarFileId}
              allowImage={!agent?.builtin}
              disabled={!editable}
              onIcon={(key) => {
                form.setFieldValue("iconName", key);
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
      <form.AppField name="taskPrompt">
        {() => (
          <TextareaField
            label={ui("Nhắc việc mỗi lượt")}
            hint={ui("Gửi kèm mỗi câu hỏi, dùng cho quy tắc ngắn mà trợ lý hay quên.")}
            maxLength={32000}
            rows={2}
            placeholder={ui("Ví dụ: luôn nêu tháng và đơn vị của báo cáo")}
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
        <form.AppField name="knowledgeCutoff">
          {(field) => (
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
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                />
              }
            />
          )}
        </form.AppField>
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
      <EditorField label={ui("Máy chủ MCP")}>
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
      description={ui("Model và giới hạn token. Để trống để dùng giới hạn của model.")}
    >
      {models.isError && (
        <LoadFailure onRetry={() => void models.refetch()}>
          {ui("Không tải được model.")}
        </LoadFailure>
      )}
      <SettingRows>
        <form.AppField name="modelConfigurationId">
          {(field) => {
            const selected = field.state.value;
            const options = [
              {
                id: autoModel,
                name: ui("Tự động"),
                description: ui("Dùng model mặc định của tổ chức"),
              },
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
                    onValueChange={(value) => field.handleChange(value === autoModel ? "" : value)}
                  >
                    <ModelSelectorTrigger
                      aria-label={ui("Model mặc định")}
                      disabled={!editable || models.isPending}
                      className="h-9 max-w-64 min-w-40"
                    />
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
                    </ModelSelectorContent>
                  </ModelSelectorRoot>
                }
              />
            );
          }}
        </form.AppField>
        <form.AppField name="contextTokenLimit">
          {(field) => (
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
                  className="w-32 text-right"
                  value={field.state.value}
                  placeholder={ui("Theo model")}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                />
              }
            />
          )}
        </form.AppField>
        <form.AppField name="outputTokenLimit">
          {(field) => (
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
                  className="w-32 text-right"
                  value={field.state.value}
                  placeholder={ui("Theo model")}
                  onBlur={field.handleBlur}
                  onChange={(event) => field.handleChange(event.target.value)}
                />
              }
            />
          )}
        </form.AppField>
      </SettingRows>
      {!agent?.builtin && (
        <SettingRows>
          <form.AppField name="replaceBaseSystemPrompt">
            {(field) => (
              <SettingRow
                htmlFor="agent-replace-base"
                title={ui("Thay hướng dẫn hệ thống mặc định")}
                description={ui("Chỉ dùng hướng dẫn của trợ lý, bỏ hướng dẫn chung của MemoryOS.")}
                control={
                  <Switch
                    id="agent-replace-base"
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
