import { useId, useState, type ReactNode } from "react";
import { revalidateLogic } from "@tanstack/react-form";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import {
  Ban,
  FileCheck,
  Globe,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Plus,
  ShieldAlert,
  Sparkles,
  Telescope,
  Trash2,
} from "lucide-react";
import { z } from "zod";
import { setServerErrors, useAppForm, useProblemErrors } from "@/components/form/app-form";
import { useFieldValidity } from "@/components/form/form-context";
import { FormDialog } from "@/components/composites/form-dialog";
import { SectionHeader } from "@/components/composites/section-header";
import { SettingRow, SettingRows } from "@/components/composites/setting-row";
import { PageHeader, SettingsLayout } from "@/components/composites/settings-layout";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import { useApplicationSession } from "@/features/identity/application-session-context";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { actionErrorText } from "@/lib/action-errors";
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
  ChatGuardrailsRequest,
  ChatGuardrailsResponse,
  ChatHistoryVisibilityRequest,
} from "@/lib/hey-api/types.gen";
import { zChatGuardrailsRequest } from "@/lib/hey-api/zod.gen";
import { presentProblem, type ErrorMessage } from "@/lib/problem-presentation";
import { useProblemMessage } from "@/lib/use-problem-message";

type ChatHistoryVisibility = ChatHistoryVisibilityRequest["visibility"];

/** The API's bounds for a topic (MEM-208, after Amazon Q Business topic controls). */
const MAX_TOPICS = 30;
const MAX_NAME = 36;
const MAX_DESCRIPTION = 350;
const MAX_EXAMPLES = 5;
const MAX_EXAMPLE = 200;
const MAX_MESSAGE = 500;

/**
 * Onyx Chat Preferences (/admin/chat-preferences), limited to what MemoryOS has: Deep research, answers from documents
 * only, who may read conversations, and the Tenant's sensitive topics and blocked phrases (MEM-195, MEM-208).
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
        <AnswersSection />
        <ConversationHistorySection />
        <GuardrailsSections />
      </div>
    </SettingsLayout>
  );
}

/** A section of the page: its heading, its content and the failure of its last change. */
function ChatSection({
  title,
  description,
  actions,
  error,
  children,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  error?: ErrorMessage;
  children: ReactNode;
}) {
  const problemMessage = useProblemMessage();
  return (
    <section aria-label={title} className="flex flex-col gap-3">
      <SectionHeader title={title} description={description} actions={actions} />
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

/**
 * How answers are made: Deep research (as Onyx Chat Preferences, offered in the composer while enabled) and answers
 * from documents only (MEM-195, after Amazon Q Business response settings), whose Web option exists only under it.
 */
function AnswersSection() {
  const ui = useAppTranslation();
  const { settings, onSuccess } = useChatSettings();
  const research = useMutation({ ...saveChatSettingsMutation(), onSuccess });
  const grounded = useMutation({ ...saveChatGroundedMutation(), onSuccess });
  const id = useId();
  if (settings.isPending) return null;
  const busy = research.isPending || grounded.isPending;
  const change = (groundedAnswers: boolean, groundedAllowWeb: boolean) =>
    settings.data &&
    grounded.mutate({
      body: { groundedAnswers, groundedAllowWeb, revision: settings.data.revision },
    });
  return (
    <ChatSection
      title={ui("Câu trả lời")}
      error={mutationError(research.error) ?? mutationError(grounded.error)}
    >
      {settings.isError ? (
        <LoadFailure />
      ) : (
        <SettingRows>
          <SettingRow
            icon={<Telescope />}
            title={ui("Deep Research")}
            htmlFor={`${id}-research`}
            description={ui(
              "Hệ thống nghiên cứu tự động trên Web và các nguồn đã kết nối. Dùng nhiều token hơn đáng kể cho mỗi câu hỏi.",
            )}
            control={
              <Switch
                id={`${id}-research`}
                checked={settings.data.deepResearchEnabled}
                disabled={busy}
                onCheckedChange={(checked) =>
                  research.mutate({
                    body: { deepResearchEnabled: checked, revision: settings.data.revision },
                  })
                }
              />
            }
          />
          <SettingRow
            icon={<FileCheck />}
            title={ui("Chỉ trả lời từ tài liệu của tổ chức")}
            htmlFor={`${id}-grounded`}
            description={ui(
              "Câu trả lời phải trích dẫn tài liệu; không có tài liệu thì trợ lý từ chối.",
            )}
            control={
              <Switch
                id={`${id}-grounded`}
                checked={settings.data.groundedAnswers}
                disabled={busy}
                onCheckedChange={(checked) => change(checked, settings.data.groundedAllowWeb)}
              />
            }
          />
          {settings.data.groundedAnswers && (
            <SettingRow
              className="pl-16"
              icon={<Globe />}
              title={ui("Cho phép người dùng bật Tìm kiếm Web")}
              htmlFor={`${id}-web`}
              description={ui("Câu trả lời dùng nguồn Internet được ghi rõ.")}
              control={
                <Switch
                  id={`${id}-web`}
                  checked={settings.data.groundedAllowWeb}
                  disabled={busy}
                  onCheckedChange={(checked) => change(settings.data.groundedAnswers, checked)}
                />
              }
            />
          )}
        </SettingRows>
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
 * Who may read other people's conversations (MEM-125): one choice of three. "Hide who asked" hides the name and the
 * e-mail and nothing else — a question often names its author — so the screen says that rather than promising
 * anonymity.
 */
function ConversationHistorySection() {
  const ui = useAppTranslation();
  const { settings, onSuccess } = useChatSettings();
  const save = useMutation({ ...saveChatHistoryVisibilityMutation(), onSuccess });
  const id = useId();
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
        <RadioGroup
          aria-label={ui("Conversation history")}
          value={settings.data.chatHistoryVisibility}
          disabled={save.isPending}
          onValueChange={(value) =>
            save.mutate({
              body: {
                visibility: value as ChatHistoryVisibility,
                revision: settings.data.revision,
              },
            })
          }
        >
          <SettingRows>
            {historyModes.map((mode) => (
              <SettingRow
                key={mode.value}
                title={ui(mode.label)}
                htmlFor={`${id}-${mode.value}`}
                description={ui(mode.detail)}
                control={<RadioGroupItem id={`${id}-${mode.value}`} value={mode.value} />}
              />
            ))}
          </SettingRows>
        </RadioGroup>
      )}
    </ChatSection>
  );
}

/** A topic as the dialog edits it: the examples are one per line. */
type TopicDraft = {
  id?: string;
  name: string;
  description: string;
  examples: string;
  message: string;
  enabled: boolean;
};

function linesOf(text: string) {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}

/**
 * The Tenant's sensitive topics (MEM-208, after Amazon Q Business topic controls) and blocked phrases. A topic switch
 * saves at once; a topic is added and edited in a dialog and deleted after confirmation. Every change sends the whole
 * guardrails document with its revision, so every guardrail control, the phrases' Save included, waits while any
 * guardrail save is in flight: a second save would carry a stale revision.
 */
function GuardrailsSections() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const guardrails = useQuery({ ...getChatGuardrailsOptions(), retry: false });
  const save = useMutation({
    ...saveChatGuardrailsMutation(),
    onSuccess: (next) => cache.setQueryData(getChatGuardrailsQueryKey(), next),
    onError: () => cache.invalidateQueries({ queryKey: getChatGuardrailsQueryKey() }),
  });
  // The phrases keep their own mutation so a failure is shown on their form, not under the topics.
  const phrasesSave = useMutation(saveChatGuardrailsMutation());
  const busy = save.isPending || phrasesSave.isPending;
  const [editing, setEditing] = useState<TopicDraft>();
  const [removing, setRemoving] = useState<ChatGuardrailTopic>();
  if (guardrails.isPending) return null;
  if (guardrails.isError)
    return (
      <ChatSection title={ui("Chủ đề nhạy cảm")}>
        <LoadFailure />
      </ChatSection>
    );
  const saved = guardrails.data;
  const saveTopics = (topics: ChatGuardrailTopic[]) =>
    save.mutateAsync({
      body: {
        topics,
        blockedPhrases: saved.blockedPhrases,
        blockedPhraseMessage: saved.blockedPhraseMessage,
        revision: saved.revision,
      },
    });
  const full = saved.topics.length >= MAX_TOPICS;
  return (
    <>
      <ChatSection
        title={ui("Chủ đề nhạy cảm")}
        error={mutationError(save.error)}
        actions={
          <Button
            prominence="secondary"
            size="sm"
            disabled={full || busy}
            title={full ? ui("Đã đủ 30 chủ đề.") : undefined}
            onClick={() =>
              setEditing({ name: "", description: "", examples: "", message: "", enabled: true })
            }
          >
            <Plus data-icon="inline-start" aria-hidden="true" />
            {ui("Thêm chủ đề")}
          </Button>
        }
      >
        <SettingRows>
          {saved.topics.length === 0 && (
            <p className="px-4 py-3 font-secondary-body text-content-muted">
              {ui("Chưa có chủ đề nào.")}
            </p>
          )}
          {saved.topics.map((topic, index) => (
            <SettingRow
              key={topic.id ?? index}
              icon={<ShieldAlert />}
              title={topic.name}
              description={topic.description}
              control={
                <div className="flex items-center gap-1">
                  <Switch
                    checked={topic.enabled}
                    aria-label={topic.name}
                    disabled={busy}
                    onCheckedChange={(enabled) =>
                      void saveTopics(
                        saved.topics.map((other) =>
                          other === topic ? { ...topic, enabled } : other,
                        ),
                      ).catch(() => undefined)
                    }
                  />
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <IconButton
                        size="sm"
                        prominence="internal"
                        disabled={busy}
                        aria-label={ui("Thao tác với {{name}}", { name: topic.name })}
                      >
                        <MoreHorizontal />
                      </IconButton>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="min-w-40">
                      <DropdownMenuGroup>
                        <DropdownMenuItem
                          onSelect={() =>
                            setEditing({
                              id: topic.id,
                              name: topic.name,
                              description: topic.description,
                              examples: topic.examples.join("\n"),
                              message: topic.message,
                              enabled: topic.enabled,
                            })
                          }
                        >
                          <Pencil /> {ui("Sửa")}
                        </DropdownMenuItem>
                      </DropdownMenuGroup>
                      <DropdownMenuSeparator />
                      <DropdownMenuGroup>
                        <DropdownMenuItem variant="destructive" onSelect={() => setRemoving(topic)}>
                          <Trash2 /> {ui("Xóa chủ đề")}
                        </DropdownMenuItem>
                      </DropdownMenuGroup>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              }
            />
          ))}
          <SettingRow
            icon={<Sparkles />}
            title={ui("Mô hình kiểm tra câu hỏi")}
            control={
              <Button asChild prominence="tertiary" size="sm">
                <Link to="/admin/ai-providers" search={{ tab: "system-one" }}>
                  {ui("Đổi")}
                </Link>
              </Button>
            }
          />
        </SettingRows>
      </ChatSection>
      <PhrasesSection
        saved={saved}
        busy={busy}
        save={(body) => phrasesSave.mutateAsync({ body })}
      />
      {editing && (
        <TopicDialog
          draft={editing}
          others={saved.topics.filter((topic) => topic.id !== editing.id)}
          onClose={() => setEditing(undefined)}
          onSave={async (topic) => {
            await saveTopics(
              editing.id
                ? saved.topics.map((other) => (other.id === editing.id ? topic : other))
                : [...saved.topics, topic],
            );
          }}
        />
      )}
      <ConfirmDialog
        open={removing !== undefined}
        onOpenChange={(open) => !open && setRemoving(undefined)}
        title={ui("Xóa chủ đề?")}
        description={ui("Câu hỏi về chủ đề này sẽ không còn bị chặn.")}
        confirmLabel={ui("Xóa chủ đề")}
        pendingLabel={ui("Đang lưu…")}
        confirmTone="danger"
        errorMessage={actionErrorText}
        onConfirm={async () => {
          if (removing) await saveTopics(saved.topics.filter((topic) => topic.id !== removing.id));
        }}
      />
    </>
  );
}

/** Adds or edits one topic; the description is what a question is matched against. */
function TopicDialog({
  draft,
  others,
  onClose,
  onSave,
}: {
  draft: TopicDraft;
  others: ChatGuardrailTopic[];
  onClose: () => void;
  onSave: (topic: ChatGuardrailTopic) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const id = useId();
  const [value, setValue] = useState(draft);
  const examples = linesOf(value.examples);
  const duplicate = others.some(
    (topic) => topic.name.trim().toLocaleLowerCase() === value.name.trim().toLocaleLowerCase(),
  );
  const examplesError =
    examples.length > MAX_EXAMPLES
      ? ui("Tối đa 5 câu ví dụ.")
      : examples.some((example) => example.length > MAX_EXAMPLE)
        ? ui("Mỗi câu ví dụ tối đa 200 ký tự.")
        : undefined;
  const invalid = !value.name.trim() || !value.description.trim() || duplicate || !!examplesError;
  return (
    <FormDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={draft.id ? ui("Sửa chủ đề") : ui("Thêm chủ đề")}
      description={ui("Mô tả là căn cứ để chặn câu hỏi.")}
      submitDisabled={invalid}
      onSubmit={async () => {
        await onSave({
          id: draft.id,
          name: value.name.trim(),
          description: value.description.trim(),
          examples,
          message: value.message.trim(),
          enabled: draft.enabled,
        });
      }}
    >
      <Field data-invalid={duplicate || undefined}>
        <FieldLabel htmlFor={`${id}-name`}>{ui("Tên chủ đề")}</FieldLabel>
        <Input
          id={`${id}-name`}
          value={value.name}
          maxLength={MAX_NAME}
          aria-invalid={duplicate || undefined}
          onChange={(event) => setValue({ ...value, name: event.target.value })}
        />
        {duplicate && <FieldError errors={[{ message: ui("Đã có chủ đề cùng tên.") }]} />}
      </Field>
      <Field>
        <FieldLabel htmlFor={`${id}-description`}>{ui("Mô tả")}</FieldLabel>
        <Textarea
          id={`${id}-description`}
          rows={3}
          value={value.description}
          maxLength={MAX_DESCRIPTION}
          onChange={(event) => setValue({ ...value, description: event.target.value })}
        />
      </Field>
      <Field data-invalid={!!examplesError || undefined}>
        <FieldLabel htmlFor={`${id}-examples`}>{ui("Câu hỏi ví dụ")}</FieldLabel>
        <Textarea
          id={`${id}-examples`}
          rows={4}
          value={value.examples}
          placeholder={ui("Mỗi dòng một câu, tối đa 5")}
          aria-invalid={!!examplesError || undefined}
          onChange={(event) => setValue({ ...value, examples: event.target.value })}
        />
        {examplesError && <FieldError errors={[{ message: examplesError }]} />}
      </Field>
      <Field>
        <FieldLabel htmlFor={`${id}-message`}>{ui("Câu trả lời khi bị chặn")}</FieldLabel>
        <Input
          id={`${id}-message`}
          value={value.message}
          maxLength={MAX_MESSAGE}
          placeholder={ui("Trợ lý không trả lời câu hỏi về chủ đề này.")}
          onChange={(event) => setValue({ ...value, message: event.target.value })}
        />
      </Field>
    </FormDialog>
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

function phrasesDraft(saved: ChatGuardrailsResponse) {
  return { phrases: saved.blockedPhrases.join("\n"), message: saved.blockedPhraseMessage };
}

/**
 * Exact phrases no question or answer may contain (MEM-195, after Amazon Q Business blocked phrases). They are typed,
 * so this card keeps its own save; the topics are sent as the latest save left them.
 */
function PhrasesSection({
  saved,
  busy,
  save,
}: {
  saved: ChatGuardrailsResponse;
  /** A guardrail save is in flight, from this card or the topics. */
  busy: boolean;
  save: (body: ChatGuardrailsRequest) => Promise<ChatGuardrailsResponse>;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemErrors = useProblemErrors();
  const schema = z.object({
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
    defaultValues: phrasesDraft(saved),
    validationLogic: revalidateLogic(),
    validators: { onDynamic: schema },
    onSubmit: async ({ value, formApi }) => {
      // The latest guardrails are read at submit: a topic switched meanwhile keeps its new state.
      const current = cache.getQueryData(getChatGuardrailsOptions().queryKey) ?? saved;
      try {
        const next = await save({
          topics: current.topics,
          blockedPhrases: phrasesOf(value.phrases),
          blockedPhraseMessage: value.message,
          revision: current.revision,
        });
        cache.setQueryData(getChatGuardrailsQueryKey(), next);
        formApi.reset(phrasesDraft(next));
      } catch (failed) {
        setServerErrors(formApi, problemErrors(failed));
        await cache.invalidateQueries({ queryKey: getChatGuardrailsQueryKey() });
      }
    },
  });
  return (
    <ChatSection title={ui("Cụm từ bị chặn")}>
      <form
        className="flex flex-col gap-4 rounded-xl border border-border-subtle bg-surface-raised p-4"
        onSubmit={(event) => {
          event.preventDefault();
          void form.handleSubmit();
        }}
      >
        <div className="flex items-start gap-3">
          <Ban className="mt-2 size-5 shrink-0 text-content-muted" aria-hidden="true" />
          <div className="flex min-w-0 flex-1 flex-col gap-4">
            <form.AppField name="phrases">
              {() => <PhrasesControl label={ui("Cụm từ bị chặn")} />}
            </form.AppField>
            <form.AppField name="message">
              {() => <MessageControl label={ui("Câu trả lời khi bị chặn")} />}
            </form.AppField>
          </div>
        </div>
        <form.AppForm>
          <form.FormError />
          <form.Subscribe selector={(state) => state.isDirty}>
            {(dirty) => (
              <div className="flex justify-end">
                <form.SubmitButton disabled={!dirty || busy}>{ui("Lưu")}</form.SubmitButton>
              </div>
            )}
          </form.Subscribe>
        </form.AppForm>
      </form>
    </ChatSection>
  );
}

function PhrasesControl({ label }: { label: string }) {
  const ui = useAppTranslation();
  const { field, invalid, errors } = useFieldValidity<string>();
  return (
    <div className="flex flex-col gap-1" data-invalid={invalid || undefined}>
      {/* The section heading already names the list on screen. */}
      <FieldLabel htmlFor={field.name} className="sr-only">
        {label}
      </FieldLabel>
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
    <div className="flex flex-col gap-1" data-invalid={invalid || undefined}>
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
