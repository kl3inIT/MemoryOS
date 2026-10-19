import { useAppTranslation } from "@/i18n/use-app-translation";
import { useChat } from "@ai-sdk/react";
import { useAISDKRuntime } from "@assistant-ui/ai-sdk";
import { AssistantRuntimeProvider } from "@assistant-ui/react";
import { useStore } from "@tanstack/react-form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { RotateCcw, Sparkles } from "lucide-react";
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { ChatUiMessage } from "@/features/chat/chat-api";
import type { Persona } from "@/features/chat/chat-personas-api";
import { chatToolDefaults, turnModelOf } from "@/features/chat/chat-tool-defaults";
import { ChatThreadController } from "@/features/chat/runtime/chat-thread-controller";
import { AssistantWelcome } from "@/features/chat/session/assistant-welcome";
import { StarterPromptButtons } from "@/features/chat/session/chat-session-settings";
import { ChatThread } from "@/features/chat/thread/chat-thread";
import { ChatReplyAddon } from "@/features/chat/thread/chat-reply-addon";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useChatPreferences } from "@/features/identity/chat-preferences";
import { useMcpConnections } from "@/features/mcp/mcp-connections";
import {
  getChatImageAvailabilityOptions,
  getChatSettingsOptions,
  getChatWebAvailabilityOptions,
  listChatPersonaModelsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { useProblemMessage } from "@/lib/use-problem-message";
import { AgentBuilderChangeCards } from "./agent-builder-card";
import { BuilderCard, type BuilderCardContext } from "./agent-builder-context";
import { BuilderChanges } from "./agent-builder-changes";
import { hasBrief, takeBrief } from "./agent-builder-handoff";
import { agentRequest } from "./agent-form";
import type { AgentChoices, AgentFormApi } from "./use-agent-editor";

type Panel = "test" | "builder";
const PANEL_KEY = "memoryos.agent-editor.panel";

function readPanel(): Panel {
  try {
    return localStorage.getItem(PANEL_KEY) === "builder" ? "builder" : "test";
  } catch {
    return "test";
  }
}

function writePanel(panel: Panel) {
  try {
    localStorage.setItem(PANEL_KEY, panel);
  } catch {
    // The tab is a convenience; the panel works without remembering it.
  }
}

/**
 * The panel beside the editor's form (MEM-273, MEM-280): *Chat thử* answers with the form as it stands, saved or not,
 * in a temporary conversation of this assistant, as FastGPT's chat test and Dify's debug panel run the unsaved app;
 * *Agent Builder* edits that form through conversation, as LobeHub's Agent Builder beside the agent profile. Both
 * stay open while the other tab shows; "Làm mới" starts the shown one again. The tab is remembered per browser.
 */
export default function AgentTestChat({
  agent,
  form,
  dirty,
  choices,
}: {
  agent: Persona;
  form: AgentFormApi;
  dirty: boolean;
  choices: AgentChoices;
}) {
  const ui = useAppTranslation();
  // The create dialog's description opens the Agent Builder whatever tab was last used.
  const [panel, setPanel] = useState<Panel>(() => (hasBrief(agent.id) ? "builder" : readPanel()));
  const [rounds, setRounds] = useState({ test: 0, builder: 0 });
  return (
    <Tabs
      value={panel}
      onValueChange={(value) => {
        setPanel(value as Panel);
        writePanel(value as Panel);
      }}
      className="flex h-full min-h-0 flex-col"
    >
      <div className="flex h-12 shrink-0 items-center gap-2 border-b border-border-subtle px-4">
        <TabsList aria-label={ui("Thử và chỉnh trợ lý")}>
          <TabsTrigger value="test">{ui("Chat thử")}</TabsTrigger>
          <TabsTrigger value="builder">
            <Sparkles aria-hidden="true" />
            {ui("Agent Builder")}
          </TabsTrigger>
        </TabsList>
        {/* On a phone the row holds only the tabs and Làm mới. */}
        {dirty && (
          <span className="hidden sm:contents">
            <StatusBadge tone="warning" size="sm">
              {ui("Đang dùng bản chưa lưu")}
            </StatusBadge>
          </span>
        )}
        <Button
          type="button"
          size="sm"
          prominence="tertiary"
          className="ml-auto"
          onClick={() => setRounds((value) => ({ ...value, [panel]: value[panel] + 1 }))}
        >
          <RotateCcw aria-hidden="true" />
          {ui("Làm mới")}
        </Button>
      </div>
      <TabsContent value="test" forceMount className="min-h-0 flex-1 data-[state=inactive]:hidden">
        <section aria-label={ui("Chat thử")} className="flex h-full min-h-0 flex-col">
          <TestThread key={rounds.test} agent={agent} form={form} />
        </section>
      </TabsContent>
      <TabsContent
        value="builder"
        forceMount
        className="min-h-0 flex-1 data-[state=inactive]:hidden"
      >
        <section aria-label={ui("Agent Builder")} className="flex h-full min-h-0 flex-col">
          <BuilderThread key={rounds.builder} agent={agent} form={form} choices={choices} />
        </section>
      </TabsContent>
    </Tabs>
  );
}

/** A temporary conversation of the assistant, created on its first message and gone with "Làm mới". */
function usePanelThread(agent: Persona, form: AgentFormApi, builder?: BuilderChanges) {
  const queries = useQueryClient();
  const identity = useApplicationSession();
  const [controller] = useState(() => {
    const created = new ChatThreadController(
      crypto.randomUUID(),
      undefined,
      queries,
      identity.actorId,
      () => {},
    );
    created.setPersona(agent.id);
    created.transport.selectTemporary(true);
    if (builder) {
      created.transport.builder = true;
      created.transport.onAssistantChange = (change) => builder.apply(change);
    }
    // Read at each send, so a message always runs (or builds on) the form as it is at that moment.
    created.transport.draftPersona = () => agentRequest(form.state.values, agent);
    return created;
  });
  const state = useSyncExternalStore(controller.subscribe, controller.getState);
  const chat = useChat<ChatUiMessage>({
    id: controller.id,
    transport: controller.transport,
    generateId: () => crypto.randomUUID(),
    sendAutomaticallyWhen: () => false,
    throttle: 50,
    onError: (cause) => controller.markUnfinished(cause),
  });
  const runtime = useAISDKRuntime(chat, {
    isSendDisabled: state.connection !== "ready" || state.unavailable || state.checking,
  });
  useEffect(() => {
    controller.connect(chat, () => runtime.thread.cancelRun());
  }, [controller, chat, runtime]);
  useEffect(() => controller.listen(), [controller]);
  // Leaving closes the reader; the temporary conversation deletes itself with its window.
  useEffect(() => () => controller.transport.disconnect(), [controller]);
  return { controller, state, runtime };
}

function useThreadStatus(state: ReturnType<typeof usePanelThread>["state"]) {
  const { t } = useTranslation("chatStatus");
  const problemMessage = useProblemMessage();
  return state.error
    ? typeof state.error === "string"
      ? t(state.error)
      : problemMessage(state.error)
    : undefined;
}

function TestThread({ agent, form }: { agent: Persona; form: AgentFormApi }) {
  const { controller, state, runtime } = usePanelThread(agent, form);
  const error = useThreadStatus(state);
  useTestTools(agent, form, controller);

  const name = useStore(form.store, (s) => s.values.name);
  const title = useStore(form.store, (s) => s.values.title);
  const description = useStore(form.store, (s) => s.values.description);
  const greeting = useStore(form.store, (s) => s.values.greeting);
  const starters = useStore(form.store, (s) => s.values.starterPrompts);
  const avatarSeed = useStore(form.store, (s) => s.values.avatarSeed);
  const keepAvatar = useStore(form.store, (s) => s.values.keepAvatar);
  const busy = state.connection !== "ready" || state.checking;
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ChatThread
        modelPicker={null}
        welcome={
          <AssistantWelcome
            level={2}
            assistant={{
              id: agent.id,
              name: name.trim() || agent.name,
              title: title.trim(),
              description,
              greeting: greeting.trim(),
              avatarSeed,
              hasAvatar: keepAvatar,
            }}
          >
            <StarterPromptButtons
              prompts={starters.map((prompt) => prompt.trim()).filter(Boolean)}
              disabled={busy}
            />
          </AssistantWelcome>
        }
        connection={state.connection}
        stopping={state.stopping}
        onStop={() => void controller.stop()}
        error={error}
        onCheck={() => controller.check()}
        checking={state.checking}
      />
    </AssistantRuntimeProvider>
  );
}

/**
 * The Agent Builder (MEM-280): each turn sends the form, the server's builder changes it only through
 * `assistant_change` events, which land in the form here and show as a card under the reply with *Hoàn tác*. Nothing
 * is saved: *Lưu* publishes, *Hủy* drops it. The create dialog's description, when there is one, is its first message.
 */
function BuilderThread({
  agent,
  form,
  choices,
}: {
  agent: Persona;
  form: AgentFormApi;
  choices: AgentChoices;
}) {
  const ui = useAppTranslation();
  const [changes] = useState(
    () =>
      new BuilderChanges(
        () => form.state.values,
        (field, value) => form.setFieldValue(field, value as never),
      ),
  );
  const { controller, state, runtime } = usePanelThread(agent, form, changes);
  const error = useThreadStatus(state);
  const nameOf = useNames(agent, choices);
  const card = useMemo<BuilderCardContext>(() => ({ changes, nameOf }), [changes, nameOf]);

  const ready = state.connection === "ready" && !state.checking && !state.unavailable;
  const briefSent = useRef(false);
  useEffect(() => {
    if (!ready || briefSent.current) return;
    briefSent.current = true;
    const brief = takeBrief(agent.id);
    if (brief)
      void runtime.thread.append({
        role: "user",
        content: [
          {
            type: "text",
            text: ui("Dựng trợ lý theo mô tả này: {{description}}", { description: brief }),
          },
        ],
      });
  }, [ready, runtime, agent.id, ui]);

  return (
    <BuilderCard value={card}>
      <ChatReplyAddon value={AgentBuilderChangeCards}>
        <AssistantRuntimeProvider runtime={runtime}>
          <ChatThread
            modelPicker={null}
            placeholder={ui("Muốn chỉnh gì ở trợ lý này?")}
            welcome={
              <div className="pt-6">
                <Sparkles className="mx-auto size-6 text-content-muted" aria-hidden="true" />
                <h2 className="mt-3 font-heading-h3 text-content-primary">
                  {ui("Bạn muốn trợ lý này làm gì?")}
                </h2>
                <StarterPromptButtons
                  prompts={[
                    ui("Viết hướng dẫn từ mô tả"),
                    ui("Thêm câu hỏi gợi ý"),
                    ui("Trả lời ngắn gọn hơn"),
                  ]}
                  disabled={!ready}
                />
              </div>
            }
            connection={state.connection}
            stopping={state.stopping}
            onStop={() => void controller.stop()}
            error={error}
            onCheck={() => controller.check()}
            checking={state.checking}
          />
        </AssistantRuntimeProvider>
      </ChatReplyAddon>
    </BuilderCard>
  );
}

/** The names the change card shows for an option, from the editor's choices and what the assistant keeps. */
function useNames(agent: Persona, choices: AgentChoices): BuilderCardContext["nameOf"] {
  const ui = useAppTranslation();
  const sources = choices.sources.data;
  const documentSets = choices.documentSets.data;
  const servers = choices.mcp.data;
  const models = choices.models.data;
  return useMemo(() => {
    const named = (options: { id?: string; name?: string }[] | undefined, id: string) =>
      options?.find((option) => option.id === id)?.name;
    return (field, id) =>
      (field === "sourceIds"
        ? (named(sources, id) ?? named(agent.sources, id))
        : field === "documentSetIds"
          ? (named(documentSets, id) ?? named(agent.documentSets, id))
          : field === "mcpServerIds"
            ? (named(servers, id) ?? named(agent.mcpServers, id))
            : (() => {
                const model = models?.find((candidate) => candidate.id === id);
                return model ? model.displayName || model.modelName : undefined;
              })()) ?? ui("Không còn khả dụng");
  }, [sources, documentSets, servers, models, agent, ui]);
}

/**
 * The tools a test question carries, decided as a conversation with the saved assistant decides them, from the
 * form's tools, model and "Chỉ trả lời có căn cứ" instead of the saved ones.
 */
function useTestTools(agent: Persona, form: AgentFormApi, controller: ChatThreadController) {
  const tools = useStore(form.store, (s) => s.values.tools);
  const servers = useStore(form.store, (s) => s.values.mcpServerIds);
  const modelId = useStore(form.store, (s) => s.values.modelConfigurationId);
  const groundedAgent = useStore(form.store, (s) => s.values.grounded);
  const identity = useApplicationSession();
  const settings = useQuery({ ...getChatSettingsOptions(), retry: false });
  const web = useQuery({ ...getChatWebAvailabilityOptions(), retry: false });
  const image = useQuery({ ...getChatImageAvailabilityOptions(), retry: false });
  const catalog = useQuery(listChatPersonaModelsOptions({ path: { personaId: agent.id } }));
  const connections = useMcpConnections();
  const preferences = useChatPreferences();
  const grounded = settings.data?.groundedAnswers === true || groundedAgent;
  const allowed = useMemo(
    () => ({
      web: tools.includes("web_search") && (!grounded || settings.data?.groundedAllowWeb === true),
      image: tools.includes("image_generation"),
      mcpServerIds: agent.builtin ? null : servers,
    }),
    [tools, servers, grounded, settings.data?.groundedAllowWeb, agent.builtin],
  );
  const model = preferences.data
    ? turnModelOf(catalog.data, [modelId, preferences.data.defaultModelId])
    : undefined;
  const mayGenerateImages = identity.capabilities.includes("IMAGE_GENERATE");
  useEffect(() => {
    controller.transport.restrictTools(allowed);
    controller.transport.defaultTools(
      chatToolDefaults({
        grounded,
        allowed,
        model,
        web: web.data,
        image: image.data,
        mayGenerateImages,
        connections: connections.data,
        deepResearch: false,
      }),
    );
  }, [
    controller,
    allowed,
    grounded,
    model,
    web.data,
    image.data,
    mayGenerateImages,
    connections.data,
  ]);
}
