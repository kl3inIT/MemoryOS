import { useAppTranslation } from "@/i18n/use-app-translation";
import { useChat } from "@ai-sdk/react";
import { useAISDKRuntime } from "@assistant-ui/ai-sdk";
import { AssistantRuntimeProvider } from "@assistant-ui/react";
import { useStore } from "@tanstack/react-form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { RotateCcw } from "lucide-react";
import { useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { StatusBadge } from "@/components/ui/status-badge";
import type { ChatUiMessage } from "@/features/chat/chat-api";
import type { Persona } from "@/features/chat/chat-personas-api";
import { chatToolDefaults, turnModelOf } from "@/features/chat/chat-tool-defaults";
import { ChatThreadController } from "@/features/chat/runtime/chat-thread-controller";
import { AssistantWelcome } from "@/features/chat/session/assistant-welcome";
import { StarterPromptButtons } from "@/features/chat/session/chat-session-settings";
import { ChatThread } from "@/features/chat/thread/chat-thread";
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
import { agentRequest } from "./agent-form";
import type { AgentFormApi } from "./use-agent-editor";

/**
 * The editor's test chat (MEM-273): the form as it stands, saved or not, answering in a temporary conversation of
 * this assistant, as FastGPT's chat test and Dify's debug panel run the unsaved app. "Làm mới" starts it again.
 */
export default function AgentTestChat({
  agent,
  form,
  dirty,
}: {
  agent: Persona;
  form: AgentFormApi;
  dirty: boolean;
}) {
  const ui = useAppTranslation();
  const [round, setRound] = useState(0);
  return (
    <section aria-labelledby="agent-test-chat" className="flex h-full min-h-0 flex-col">
      <div className="flex h-12 shrink-0 items-center gap-2 border-b border-border-subtle px-4">
        <h2 id="agent-test-chat" className="font-main-ui-action text-content-primary">
          {ui("Chat thử")}
        </h2>
        {dirty && (
          <StatusBadge tone="warning" size="sm">
            {ui("Đang dùng bản chưa lưu")}
          </StatusBadge>
        )}
        <Button
          type="button"
          size="sm"
          prominence="tertiary"
          className="ml-auto"
          onClick={() => setRound((value) => value + 1)}
        >
          <RotateCcw aria-hidden="true" />
          {ui("Làm mới")}
        </Button>
      </div>
      <TestThread key={round} agent={agent} form={form} />
    </section>
  );
}

/** One test conversation: created on its first question, temporary, and gone with "Làm mới". */
function TestThread({ agent, form }: { agent: Persona; form: AgentFormApi }) {
  const { t } = useTranslation("chatStatus");
  const problemMessage = useProblemMessage();
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
    // Read at each send, so a question always runs the form as it is at that moment.
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
        error={
          state.error
            ? typeof state.error === "string"
              ? t(state.error)
              : problemMessage(state.error)
            : undefined
        }
        onCheck={() => controller.check()}
        checking={state.checking}
      />
    </AssistantRuntimeProvider>
  );
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
