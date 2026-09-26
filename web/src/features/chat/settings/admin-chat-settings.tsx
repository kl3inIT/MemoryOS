import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Ban,
  FileCheck,
  Globe,
  MessageSquare,
  MessagesSquare,
  ShieldAlert,
  Telescope,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { SettingsLayout, PageHeader } from "@/components/ui/settings-layout";
import { Switch } from "@/components/ui/switch";
import { ProviderCard } from "@/components/provider-logos/provider-card";
import { useApplicationSession } from "@/features/identity/application-session-context";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  getChatGuardrails,
  getChatSettings,
  saveChatGrounded,
  saveChatGuardrails,
  saveChatHistoryVisibility,
  saveChatSettings,
} from "@/lib/hey-api/sdk.gen";
import type { ChatGuardrailTopic, ChatHistoryVisibilityRequest } from "@/lib/hey-api/types.gen";
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
      <DeepResearchSection />
      <ConversationHistorySection />
      <GroundedSection />
      <GuardrailsSection />
    </SettingsLayout>
  );
}

function useChatSettings() {
  const session = useApplicationSession();
  return useQuery({
    queryKey: ["chat-settings", session.actorId, session.authorizationVersion],
    queryFn: async ({ signal }) => (await getChatSettings({ signal })).data,
    retry: false,
  });
}

function SectionError({ error }: { error?: ErrorMessage }) {
  const problemMessage = useProblemMessage();
  return error ? (
    <p role="alert" className="text-sm text-status-danger-content">
      {problemMessage(error)}
    </p>
  ) : null;
}

/** As Onyx Chat Preferences: Deep research is offered in the composer while enabled, and is enabled until changed. */
function DeepResearchSection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const settings = useChatSettings();
  async function toggle(enabled: boolean) {
    if (!settings.data) return;
    setPending(true);
    setError(undefined);
    try {
      await saveChatSettings({
        body: { deepResearchEnabled: enabled, revision: settings.data.revision },
      });
      await cache.invalidateQueries({ queryKey: ["chat-settings"] });
    } catch (failed) {
      setError(presentProblem(failed, "mutation").message);
    } finally {
      setPending(false);
    }
  }
  if (settings.isPending) return null;
  return (
    <section aria-label={ui("Deep Research")} className="mt-8 space-y-3">
      <h2 className="text-lg font-semibold">{ui("Deep Research")}</h2>
      {settings.isError ? (
        <p role="alert">{ui("Không tải được cài đặt Chat.")}</p>
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
              disabled={pending}
              aria-label={ui("Bật Deep Research")}
              onCheckedChange={(checked) => void toggle(checked)}
            />
          }
        />
      )}
      <SectionError error={error} />
    </section>
  );
}

/**
 * Who may read other people's conversations (MEM-125). "Hide who asked" hides the name and the e-mail and nothing
 * else — a question often names its author — so the screen says that rather than promising anonymity.
 */
function ConversationHistorySection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const settings = useChatSettings();
  async function choose(visibility: ChatHistoryVisibility) {
    if (!settings.data) return;
    setPending(true);
    setError(undefined);
    try {
      await saveChatHistoryVisibility({
        body: { visibility, revision: settings.data.revision },
      });
      await cache.invalidateQueries({ queryKey: ["chat-settings"] });
    } catch (failed) {
      setError(presentProblem(failed, "mutation").message);
    } finally {
      setPending(false);
    }
  }
  if (settings.isPending) return null;
  const modes: { value: ChatHistoryVisibility; label: AppCopy; detail: AppCopy }[] = [
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
  return (
    <section aria-label={ui("Conversation history")} className="mt-8 space-y-3">
      <h2 className="text-lg font-semibold">{ui("Conversation history")}</h2>
      <p className="text-content-muted">
        {ui(
          "Who may read the organization's questions and answers. Opening a conversation is recorded in the audit log.",
        )}
      </p>
      {settings.isError ? (
        <p role="alert">{ui("Không tải được cài đặt Chat.")}</p>
      ) : (
        <div className="flex flex-col gap-2">
          {modes.map((mode) => (
            <ProviderCard
              key={mode.value}
              logo={<MessagesSquare />}
              name={ui(mode.label)}
              description={ui(mode.detail)}
              selected={settings.data.chatHistoryVisibility === mode.value}
              actions={
                <Switch
                  checked={settings.data.chatHistoryVisibility === mode.value}
                  disabled={pending}
                  aria-label={ui(mode.label)}
                  onCheckedChange={(checked) => (checked ? void choose(mode.value) : undefined)}
                />
              }
            />
          ))}
        </div>
      )}
      <SectionError error={error} />
    </section>
  );
}

/** MEM-195, after Amazon Q Business response settings: answers only from documents, and whether Web may be turned on. */
function GroundedSection() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const settings = useChatSettings();
  async function save(groundedAnswers: boolean, groundedAllowWeb: boolean) {
    if (!settings.data) return;
    setPending(true);
    setError(undefined);
    try {
      await saveChatGrounded({
        body: { groundedAnswers, groundedAllowWeb, revision: settings.data.revision },
      });
      await cache.invalidateQueries({ queryKey: ["chat-settings"] });
    } catch (failed) {
      setError(presentProblem(failed, "mutation").message);
    } finally {
      setPending(false);
    }
  }
  if (settings.isPending) return null;
  return (
    <section aria-label={ui("Trả lời từ tài liệu")} className="mt-8 space-y-3">
      <h2 className="text-lg font-semibold">{ui("Trả lời từ tài liệu")}</h2>
      {settings.isError ? (
        <p role="alert">{ui("Không tải được cài đặt Chat.")}</p>
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
                disabled={pending}
                aria-label={ui("Chỉ trả lời từ tài liệu của tổ chức")}
                onCheckedChange={(checked) => void save(checked, settings.data.groundedAllowWeb)}
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
                disabled={pending || !settings.data.groundedAnswers}
                aria-label={ui("Cho phép người dùng bật Tìm kiếm Web")}
                onCheckedChange={(checked) => void save(settings.data.groundedAnswers, checked)}
              />
            }
          />
        </div>
      )}
      <SectionError error={error} />
    </section>
  );
}

type Draft = { topics: ChatGuardrailTopic[]; phrases: string; message: string };

/** MEM-195, after Amazon Q Business topic controls and blocked phrases: the three built-in topics and exact phrases. */
function GuardrailsSection() {
  const ui = useAppTranslation();
  const session = useApplicationSession();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<ErrorMessage>();
  const [draft, setDraft] = useState<Draft>();
  const guardrails = useQuery({
    queryKey: ["chat-guardrails", session.actorId, session.authorizationVersion],
    queryFn: async ({ signal }) => (await getChatGuardrails({ signal })).data,
    retry: false,
  });
  const saved = guardrails.data;
  // The draft exists only while the manager edits; otherwise the page shows what is saved.
  const view: Draft | undefined =
    draft ??
    (saved && {
      topics: saved.topics,
      phrases: saved.blockedPhrases.join("\n"),
      message: saved.blockedPhraseMessage,
    });
  const dirty = draft !== undefined;
  function topic(key: Topic, change: Partial<ChatGuardrailTopic>) {
    if (!view) return;
    setDraft({
      ...view,
      topics: view.topics.map((entry) => (entry.topic === key ? { ...entry, ...change } : entry)),
    });
  }
  async function save() {
    if (!saved || !view) return;
    setPending(true);
    setError(undefined);
    try {
      await saveChatGuardrails({
        body: {
          topics: view.topics,
          blockedPhrases: view.phrases
            .split("\n")
            .map((phrase) => phrase.trim())
            .filter(Boolean),
          blockedPhraseMessage: view.message,
          revision: saved.revision,
        },
      });
      await guardrails.refetch();
      setDraft(undefined);
    } catch (failed) {
      setError(presentProblem(failed, "mutation").message);
      // A conflict means another manager saved first; the draft stays and the next save carries
      // the current revision.
      await guardrails.refetch();
    } finally {
      setPending(false);
    }
  }
  if (guardrails.isPending) return null;
  return (
    <section aria-label={ui("Chủ đề nhạy cảm")} className="mt-8 space-y-3">
      <h2 className="text-lg font-semibold">{ui("Chủ đề nhạy cảm")}</h2>
      {guardrails.isError || !view ? (
        <p role="alert">{ui("Không tải được cài đặt Chat.")}</p>
      ) : (
        <>
          <div className="flex flex-col gap-2">
            {view.topics.map((entry) => (
              <ProviderCard
                key={entry.topic}
                logo={<ShieldAlert />}
                name={ui(TOPIC_NAMES[entry.topic])}
                selected={entry.enabled}
                actions={
                  <Switch
                    checked={entry.enabled}
                    disabled={pending}
                    aria-label={ui(TOPIC_NAMES[entry.topic])}
                    onCheckedChange={(enabled) => topic(entry.topic, { enabled })}
                  />
                }
              >
                {entry.enabled ? (
                  <Input
                    className="basis-full"
                    value={entry.message}
                    maxLength={500}
                    disabled={pending}
                    aria-label={ui("Câu trả lời khi bị chặn")}
                    onChange={(event) => topic(entry.topic, { message: event.target.value })}
                  />
                ) : null}
              </ProviderCard>
            ))}
            <ProviderCard
              logo={<Ban />}
              name={<label htmlFor="chat-blocked-phrases">{ui("Cụm từ bị chặn")}</label>}
              selected={view.phrases.trim().length > 0}
            >
              <Textarea
                id="chat-blocked-phrases"
                className="basis-full"
                rows={3}
                value={view.phrases}
                disabled={pending}
                placeholder={ui("Mỗi dòng một cụm từ, tối đa 20")}
                onChange={(event) => setDraft({ ...view, phrases: event.target.value })}
              />
              <label
                htmlFor="chat-blocked-message"
                className="basis-full text-sm text-content-secondary"
              >
                {ui("Câu trả lời khi bị chặn")}
              </label>
              <Input
                id="chat-blocked-message"
                className="basis-full"
                value={view.message}
                maxLength={500}
                disabled={pending}
                onChange={(event) => setDraft({ ...view, message: event.target.value })}
              />
            </ProviderCard>
          </div>
          <div className="flex justify-end">
            <Button disabled={!dirty || pending} onClick={() => void save()}>
              {ui("Lưu")}
            </Button>
          </div>
        </>
      )}
      <SectionError error={error} />
    </section>
  );
}
