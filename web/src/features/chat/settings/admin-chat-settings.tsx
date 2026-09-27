import type { ReactNode } from "react";
import { revalidateLogic } from "@tanstack/react-form";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Ban,
  FileCheck,
  Globe,
  MessageSquare,
  MessagesSquare,
  ShieldAlert,
  Telescope,
} from "lucide-react";
import { z } from "zod";
import { setServerErrors, useAppForm, useProblemErrors } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import { SectionHeader } from "@/components/composites/section-header";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useApplicationSession } from "@/features/identity/application-session-context";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatGuardrailsOptions,
  getChatGuardrailsQueryKey,
  getChatSettingsOptions,
  getChatSettingsQueryKey,
  saveChatGroundedMutation,
  saveChatGuardrailsMutation,
  saveChatHistoryVisibilityMutation,
  saveChatSettingsMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  ChatGuardrailTopic,
  ChatGuardrailsResponse,
  ChatHistoryVisibilityRequest,
} from "@/lib/hey-api/types.gen";
import { zChatGuardrailsRequest, zChatGuardrailTopic } from "@/lib/hey-api/zod.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

type ChatHistoryVisibility = ChatHistoryVisibilityRequest["visibility"];
type Topic = ChatGuardrailTopic["topic"];

const TOPIC_NAMES: Record<Topic, AppCopy> = {
  POLITICS: "Chính trị",
  LEADERS: "Lãnh tụ và lãnh đạo",
  RELIGION: "Tôn giáo",
};

/**
 * Onyx Chat Preferences (/admin/chat-preferences), limited to what MemoryOS has: Deep research, who may read
 * conversations, answers from documents only and the sensitive-topic guardrails (MEM-195).
 */
export function AdminChatSettings() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  if (!session.capabilities.includes("MODELS_MANAGE"))
    return (
      <p role="alert" className="p-6">
        {ui("Bạn không có quyền quản lý mô hình.")}
      </p>
    );
  return (
    <SettingsLayout>
      <PageHeader title={ui("Chat")} icon={<MessageSquare />} />
      <div className="flex flex-col gap-8">
        <DeepResearchSection />
        <ConversationHistorySection />
        <GroundedSection />
        <GuardrailsSection />
      </div>
    </SettingsLayout>
  );
}

/** A section of the page: its heading, its content and the failure of its last change. */
function ChatSection({
  title,
  description,
  error,
  children,
}: {
  title: string;
  description?: ReactNode;
  error?: ErrorMessage;
  children: ReactNode;
}) {
  const problemMessage = useProblemMessage();
  return (
    <section aria-label={title} className="flex flex-col gap-3">
      <SectionHeader title={title} description={description} />
      {children}
      {error && (
        <Alert variant="destructive">
          <AlertTitle>{problemMessage(error)}</AlertTitle>
        </Alert>
      )}
    </section>
  );
}

function LoadFailure() {
  const ui = useAppTranslation();
  return (
    <Alert variant="destructive">
      <AlertTitle>{ui("Không tải được cài đặt Chat.")}</AlertTitle>
    </Alert>
  );
}

/** The Tenant Chat settings; a change to them refreshes every read of the settings. */
function useChatSettings() {
  const cache = useQueryClient();
  const settings = useQuery({ ...getChatSettingsOptions(), retry: false });
  const onSuccess = () => cache.invalidateQueries({ queryKey: getChatSettingsQueryKey() });
  return { settings, onSuccess };
}

function mutationError(error: unknown) {
  return error ? presentProblem(error, "mutation").message : undefined;
}

/** As Onyx Chat Preferences: Deep research is offered in the composer while enabled, and is enabled until changed. */
function DeepResearchSection() {
  const ui = useAppTranslation();
  const { settings, onSuccess } = useChatSettings();
  const save = useMutation({ ...saveChatSettingsMutation(), onSuccess });
  if (settings.isPending) return null;
  return (
    <ChatSection title={ui("Deep Research")} error={mutationError(save.error)}>
      {settings.isError ? (
        <LoadFailure />
      ) : (
        <ProviderCard
          logo={<Telescope />}
          name={ui("Deep Research")}
          description={ui(
            "Hệ thống nghiên cứu tự động trên Web và các nguồn đã kết nối. Dùng nhiều token hơn đáng kể cho mỗi câu hỏi.",
          )}
          selected={settings.data.deepResearchEnabled}
          actions={
            <Switch
              checked={settings.data.deepResearchEnabled}
              disabled={save.isPending}
              aria-label={ui("Bật Deep Research")}
              onCheckedChange={(checked) =>
                save.mutate({
                  body: { deepResearchEnabled: checked, revision: settings.data.revision },
                })
              }
            />
          }
        />
      )}
    </ChatSection>
  );
}

const historyModes: { value: ChatHistoryVisibility; label: AppCopy; detail: AppCopy }[] = [
  {
    value: "NORMAL",
    label: "Show who asked",
    detail: "A reader sees the name and e-mail of the person who asked.",
  },
  {
    value: "ANONYMIZED",
    label: "Hide who asked",
    detail:
      "The name and e-mail are hidden; the questions and answers are not. A question often names its author.",
  },
  {
    value: "DISABLED",
    label: "Nobody reads other people's conversations",
    detail: "Conversations are still recorded; this screen and its export are refused.",
  },
];

/**
 * Who may read other people's conversations (MEM-125). "Hide who asked" hides the name and the e-mail and nothing
 * else — a question often names its author — so the screen says that rather than promising anonymity.
 */
function ConversationHistorySection() {
  const ui = useAppTranslation();
  const { settings, onSuccess } = useChatSettings();
  const save = useMutation({ ...saveChatHistoryVisibilityMutation(), onSuccess });
  if (settings.isPending) return null;
  return (
    <ChatSection
      title={ui("Conversation history")}
      description={ui(
        "Who may read the organization's questions and answers. Opening a conversation is recorded in the audit log.",
      )}
      error={mutationError(save.error)}
    >
      {settings.isError ? (
        <LoadFailure />
      ) : (
        <div className="flex flex-col gap-2">
          {historyModes.map((mode) => (
            <ProviderCard
              key={mode.value}
              logo={<MessagesSquare />}
              name={ui(mode.label)}
              description={ui(mode.detail)}
              selected={settings.data.chatHistoryVisibility === mode.value}
              actions={
                <Switch
                  checked={settings.data.chatHistoryVisibility === mode.value}
                  disabled={save.isPending}
                  aria-label={ui(mode.label)}
                  onCheckedChange={(checked) => {
                    if (checked)
                      save.mutate({
                        body: { visibility: mode.value, revision: settings.data.revision },
                      });
                  }}
                />
              }
            />
          ))}
        </div>
      )}
    </ChatSection>
  );
}

/** MEM-195, after Amazon Q Business response settings: answers only from documents, and whether Web may be turned on. */
function GroundedSection() {
  const ui = useAppTranslation();
  const { settings, onSuccess } = useChatSettings();
  const save = useMutation({ ...saveChatGroundedMutation(), onSuccess });
  if (settings.isPending) return null;
  const change = (groundedAnswers: boolean, groundedAllowWeb: boolean) =>
    settings.data &&
    save.mutate({
      body: { groundedAnswers, groundedAllowWeb, revision: settings.data.revision },
    });
  return (
    <ChatSection title={ui("Trả lời từ tài liệu")} error={mutationError(save.error)}>
      {settings.isError ? (
        <LoadFailure />
      ) : (
        <div className="flex flex-col gap-2">
          <ProviderCard
            logo={<FileCheck />}
            name={ui("Chỉ trả lời từ tài liệu của tổ chức")}
            description={ui(
              "Câu trả lời phải trích dẫn tài liệu; không có tài liệu thì trợ lý từ chối.",
            )}
            selected={settings.data.groundedAnswers}
            actions={
              <Switch
                checked={settings.data.groundedAnswers}
                disabled={save.isPending}
                aria-label={ui("Chỉ trả lời từ tài liệu của tổ chức")}
                onCheckedChange={(checked) => change(checked, settings.data.groundedAllowWeb)}
              />
            }
          />
          <ProviderCard
            logo={<Globe />}
            name={ui("Cho phép người dùng bật Tìm kiếm Web")}
            description={ui("Câu trả lời dùng nguồn Internet được ghi rõ.")}
            selected={settings.data.groundedAnswers && settings.data.groundedAllowWeb}
            actions={
              <Switch
                checked={settings.data.groundedAllowWeb}
                disabled={save.isPending || !settings.data.groundedAnswers}
                aria-label={ui("Cho phép người dùng bật Tìm kiếm Web")}
                onCheckedChange={(checked) => change(settings.data.groundedAnswers, checked)}
              />
            }
          />
        </div>
      )}
    </ChatSection>
  );
}

const MAX_PHRASES = 20;
const MAX_PHRASE_LENGTH = 100;

/** The blocked phrases as the manager types them: one per line, blank lines ignored. */
function phrasesOf(text: string) {
  return text
    .split("\n")
    .map((phrase) => phrase.trim())
    .filter(Boolean);
}

function guardrailsDraft(saved: ChatGuardrailsResponse) {
  return {
    topics: saved.topics,
    phrases: saved.blockedPhrases.join("\n"),
    message: saved.blockedPhraseMessage,
  };
}

/** MEM-195, after Amazon Q Business topic controls and blocked phrases: the three built-in topics and exact phrases. */
function GuardrailsSection() {
  const ui = useAppTranslation();
  const guardrails = useQuery({ ...getChatGuardrailsOptions(), retry: false });
  if (guardrails.isPending) return null;
  return (
    <ChatSection title={ui("Chủ đề nhạy cảm")}>
      {guardrails.isError ? <LoadFailure /> : <GuardrailsForm saved={guardrails.data} />}
    </ChatSection>
  );
}

function GuardrailsForm({ saved }: { saved: ChatGuardrailsResponse }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemErrors = useProblemErrors();
  const save = useMutation(saveChatGuardrailsMutation());
  const schema = z.object({
    topics: z.array(zChatGuardrailTopic),
    phrases: z
      .string()
      .refine((text) => phrasesOf(text).length <= MAX_PHRASES, ui("Tối đa 20 cụm từ."))
      .refine(
        (text) => phrasesOf(text).every((phrase) => phrase.length <= MAX_PHRASE_LENGTH),
        ui("Mỗi cụm từ tối đa 100 ký tự."),
      ),
    message: zChatGuardrailsRequest.shape.blockedPhraseMessage.unwrap(),
  });
  const form = useAppForm({
    defaultValues: guardrailsDraft(saved),
    validationLogic: revalidateLogic(),
    validators: { onDynamic: schema },
    onSubmit: async ({ value, formApi }) => {
      try {
        // The saved revision is read at submit: after a conflict the refreshed guardrails carry the current one
        // while the draft stays.
        const next = await save.mutateAsync({
          body: {
            topics: value.topics,
            blockedPhrases: phrasesOf(value.phrases),
            blockedPhraseMessage: value.message,
            revision: saved.revision,
          },
        });
        cache.setQueryData(getChatGuardrailsQueryKey(), next);
        formApi.reset(guardrailsDraft(next));
      } catch (failed) {
        setServerErrors(formApi, problemErrors(failed));
        await cache.invalidateQueries({ queryKey: getChatGuardrailsQueryKey() });
      }
    },
  });
  return (
    <form
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <form.Field name="topics">
        {(topics) => (
          <div className="flex flex-col gap-2">
            {topics.state.value.map((entry, index) => (
              <ProviderCard
                key={entry.topic}
                logo={<ShieldAlert />}
                name={ui(TOPIC_NAMES[entry.topic])}
                selected={entry.enabled}
                actions={
                  <form.Field name={`topics[${index}].enabled`}>
                    {(field) => (
                      <Switch
                        checked={field.state.value}
                        aria-label={ui(TOPIC_NAMES[entry.topic])}
                        onCheckedChange={field.handleChange}
                      />
                    )}
                  </form.Field>
                }
              >
                {entry.enabled ? (
                  <form.Field name={`topics[${index}].message`}>
                    {(field) => (
                      <Input
                        className="basis-full"
                        value={field.state.value}
                        maxLength={500}
                        aria-label={ui("Câu trả lời khi bị chặn")}
                        onBlur={field.handleBlur}
                        onChange={(event) => field.handleChange(event.target.value)}
                      />
                    )}
                  </form.Field>
                ) : null}
              </ProviderCard>
            ))}
          </div>
        )}
      </form.Field>
      <form.Subscribe selector={(state) => state.values.phrases.trim().length > 0}>
        {(hasPhrases) => (
          <ProviderCard
            logo={<Ban />}
            name={<label htmlFor="phrases">{ui("Cụm từ bị chặn")}</label>}
            selected={hasPhrases}
          >
            <form.AppField name="phrases">{() => <PhrasesControl />}</form.AppField>
            <form.AppField name="message">
              {() => <MessageControl label={ui("Câu trả lời khi bị chặn")} />}
            </form.AppField>
          </ProviderCard>
        )}
      </form.Subscribe>
      <form.AppForm>
        <form.FormError />
        <form.Subscribe selector={(state) => state.isDirty}>
          {(dirty) => (
            <div className="flex justify-end">
              <form.SubmitButton disabled={!dirty}>{ui("Lưu")}</form.SubmitButton>
            </div>
          )}
        </form.Subscribe>
      </form.AppForm>
    </form>
  );
}

function PhrasesControl() {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <div className="flex basis-full flex-col gap-1" data-invalid={invalid || undefined}>
      <Textarea
        id={field.name}
        name={field.name}
        rows={3}
        value={field.state.value}
        placeholder={ui("Mỗi dòng một cụm từ, tối đa 20")}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      />
      {invalid ? <FieldError errors={errors} /> : null}
    </div>
  );
}

function MessageControl({ label }: { label: string }) {
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <div className="flex basis-full flex-col gap-1" data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={field.name}>{label}</FieldLabel>
      <Input
        id={field.name}
        name={field.name}
        value={field.state.value}
        maxLength={500}
        aria-invalid={invalid || undefined}
        onBlur={field.handleBlur}
        onChange={(event) => field.handleChange(event.target.value)}
      />
      {invalid ? <FieldError errors={errors} /> : null}
    </div>
  );
}
