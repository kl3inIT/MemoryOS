import { randomUUID } from "node:crypto";
import { expect, test } from "@playwright/test";
import type {
  CancelChatMessageResponse,
  ChatMessage,
  ChatSession,
  CurrentIdentity,
  PersonaModel,
  SendChatMessageResponse,
} from "../../src/lib/hey-api/types.gen.ts";

test("real manager login, selected local Chat, persisted history and active Stop", async ({
  page,
  context,
}) => {
  // Collection must work without protected staging configuration or credentials.
  const required = (name: string): string => {
    const value = process.env[`MEMORYOS_SMOKE_${name}`];
    if (!value) throw new Error("Missing staging configuration");
    return value;
  };
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  let app: URL | undefined;
  let session: string | undefined;
  let reply: string | undefined;
  let phase = "configuration";
  let failure: string | undefined;

  async function api<T = void>(
    path: string,
    method = "GET",
    data?: object,
    status = 200,
  ): Promise<T> {
    if (!app) throw new Error("Missing application origin");
    const response = await context.request.fetch(new URL(path, app).href, {
      method,
      data,
      headers: { "X-MemoryOS-CSRF": "1", Origin: app.origin },
      maxRedirects: 0,
      timeout: 30_000,
    });
    try {
      expect(response.status() === status).toBe(true);
      return (status === 204 ? undefined : await response.json()) as T;
    } finally {
      await response.dispose();
    }
  }

  const history = () => api<ChatMessage[]>(`/api/chat/sessions/${session}/messages?limit=100`);
  const stopButton = page.getByRole("button", { name: "Dừng trả lời", exact: true });
  const answers = page.getByTestId("chat-viewport").locator(".aui-md");

  async function send(text: string, modelId: string): Promise<SendChatMessageResponse> {
    await page.getByRole("textbox", { name: "Câu hỏi", exact: true }).fill(text);
    // Subscribe before the click; never race a detached send against Stop.
    const [response] = await Promise.all([
      page.waitForResponse(
        (response) =>
          new URL(response.url()).origin === app?.origin &&
          new URL(response.url()).pathname === `/api/chat/sessions/${session}/messages` &&
          response.request().method() === "POST",
        { timeout: 30_000 },
      ),
      page.getByRole("button", { name: "Gửi câu hỏi", exact: true }).click(),
    ]);
    expect(response.status() === 202).toBe(true);
    const accepted = (await response.json()) as SendChatMessageResponse;
    expect(uuid.test(accepted.assistantMessageId) && uuid.test(accepted.userMessageId)).toBe(true);
    reply = accepted.assistantMessageId;
    expect(accepted.userMessageId !== reply).toBe(true);
    expect(accepted.modelConfigurationId === modelId && accepted.fallbackReason == null).toBe(true);
    return accepted;
  }

  async function terminal(messageId: string): Promise<ChatMessage> {
    let message: ChatMessage | undefined;
    await expect
      .poll(
        async () => {
          message = (await history()).find((item) => item.id === messageId);
          return message?.role === "ASSISTANT" && message.status !== "RUNNING";
        },
        { timeout: 135_000, intervals: [250, 500, 1_000] },
      )
      .toBe(true);
    if (!message) throw new Error("Missing accepted reply");
    return message;
  }

  try {
    const username = required("CHAT_USERNAME");
    const password = required("CHAT_PASSWORD");
    const actorId = required("CHAT_ACTOR_ID").toLowerCase();
    const modelId = required("CHAT_MODEL_ID").toLowerCase();
    app = new URL(required("ORIGIN"));
    const issuer = new URL(required("ISSUER"));
    if (
      [app, issuer].some((url) => url.protocol !== "https:" || url.username || url.password) ||
      app.href !== `${app.origin}/` ||
      issuer.search ||
      issuer.hash ||
      !uuid.test(actorId) ||
      !uuid.test(modelId)
    ) {
      throw new Error("Invalid staging configuration");
    }

    phase = "Keycloak login";
    await page.goto(new URL("/oauth2/authorization/memoryos", app).href);
    const loginUrl = new URL(page.url());
    expect(
      loginUrl.origin === issuer.origin &&
        loginUrl.pathname.startsWith(`${issuer.pathname.replace(/\/$/, "")}/`),
    ).toBe(true);
    await page.locator('#kc-form-login input[name="username"]').fill(username);
    await page.locator('#kc-form-login input[name="password"]').fill(password);
    await page.locator('#kc-form-login [type="submit"]').click();
    await page.waitForURL((url) => url.origin === app?.origin);
    const identity = await api<CurrentIdentity>("/api/identity/me");
    expect(identity.actorId === actorId && identity.tenant !== null).toBe(true);
    expect(identity.capabilities.includes("MODELS_MANAGE")).toBe(true);

    phase = "owned session and configured Persona";
    const created = await api<ChatSession>(
      "/api/chat/sessions",
      "POST",
      { title: `MEMORYOS-SMOKE-CHAT-${randomUUID()}` },
      201,
    );
    expect(uuid.test(created.id)).toBe(true);
    session = created.id;
    console.log(`Smoke-owned Chat session created: ${session}`);
    expect(uuid.test(created.personaId) && uuid.test(created.rootMessageId)).toBe(true);
    const selection = await api<PersonaModel>(`/api/chat/personas/${created.personaId}/model`);
    expect(
      selection.personaId === created.personaId && selection.modelConfigurationId === modelId,
    ).toBe(true);
    // Selection is a protected target prerequisite, not something this test grants
    // or changes. Auto mode inherits this Persona's configured model.
    await page.goto(new URL(`/chat/${session}`, app).href);
    const modelPicker = page.getByRole("combobox", { name: "Chọn mô hình", exact: true });
    await modelPicker.click();
    await page.getByRole("option", { name: "Tự động", exact: true }).click();
    await expect(modelPicker).toContainText("Tự động");

    phase = "selected local stream";
    const firstText = "My name is Linh. Tên tôi là Linh. Say hello in two short English sentences.";
    const [stream, first] = await Promise.all([
      page.waitForResponse(
        (response) =>
          new URL(response.url()).origin === app?.origin &&
          new URL(response.url()).pathname.startsWith(`/api/chat/sessions/${session}/messages/`) &&
          new URL(response.url()).pathname.endsWith("/events") &&
          response.request().method() === "GET",
        { timeout: 45_000 },
      ),
      send(firstText, modelId),
    ]);
    expect(
      stream.status() === 200 &&
        stream.headers()["content-type"]?.includes("text/event-stream") === true &&
        new URL(stream.url()).pathname ===
          `/api/chat/sessions/${session}/messages/${first.assistantMessageId}/events`,
    ).toBe(true);
    await expect(answers).toHaveCount(1, { timeout: 135_000 });
    await expect
      .poll(async () => (await answers.first().innerText()).trim().length > 0, {
        timeout: 135_000,
      })
      .toBe(true);
    const completed = await terminal(first.assistantMessageId);
    expect(completed.status === "COMPLETED" && completed.content.trim().length > 0).toBe(true);
    await expect(stopButton).toHaveCount(0);
    const renderedAnswer = await answers.first().innerText();

    phase = "persisted history reload";
    const saved = await history();
    const question = saved.find((item) => item.id === first.userMessageId);
    expect(
      saved.length === 2 &&
        question?.role === "USER" &&
        question.content === firstText &&
        question.parentMessageId === created.rootMessageId &&
        completed.sessionId === session &&
        completed.parentMessageId === first.userMessageId &&
        completed.finishedAt !== null,
    ).toBe(true);
    await page.reload();
    await expect(answers).toHaveCount(1);
    await expect
      .poll(async () => (await answers.first().innerText()) === renderedAnswer)
      .toBe(true);
    expect((await page.getByTestId("chat-viewport").innerText()).includes(firstText)).toBe(true);
    const restored = (await history()).find((item) => item.id === first.assistantMessageId);
    expect(restored?.status === "COMPLETED" && restored.content === completed.content).toBe(true);

    phase = "active reply Stop";
    // Explicitly request more than the installed model's finite output allowance.
    // No max-token/default mutation or artificial delay can make an early EOS active.
    const stopping = await send(
      "Count from 1 to 200, writing each number and its English word on a separate line. " +
        "Start with 1: one. Continue in order without a summary or an early ending.",
      modelId,
    );
    const active = (await history()).find((item) => item.id === stopping.assistantMessageId);
    expect(active?.role === "ASSISTANT" && active.status === "RUNNING").toBe(true);
    const [cancellation] = await Promise.all([
      page.waitForResponse(
        (response) =>
          new URL(response.url()).origin === app?.origin &&
          new URL(response.url()).pathname ===
            `/api/chat/sessions/${session}/messages/${stopping.assistantMessageId}/cancel` &&
          response.request().method() === "POST",
        { timeout: 20_000 },
      ),
      stopButton.click({ timeout: 10_000 }),
    ]);
    expect(cancellation.status() === 202).toBe(true);
    const cancellationState = (await cancellation.json()) as CancelChatMessageResponse;
    expect(
      cancellationState.assistantMessageId === stopping.assistantMessageId &&
        ["RUNNING", "CANCELED"].includes(cancellationState.status),
    ).toBe(true);
    const stopped = await terminal(stopping.assistantMessageId);
    // A completion that wins the real race is not evidence that Stop worked.
    expect(stopped.status === "CANCELED" && stopped.finishedAt !== null).toBe(true);
    await expect(stopButton).toHaveCount(0);
    await expect(
      page.getByTestId("chat-viewport").getByText("Đã dừng", { exact: true }),
    ).toBeVisible();
    await page.reload();
    await expect(
      page.getByTestId("chat-viewport").getByText("Đã dừng", { exact: true }),
    ).toBeVisible();
    const reloaded = (await history()).find((item) => item.id === stopping.assistantMessageId);
    expect(reloaded?.status === "CANCELED" && reloaded.content === stopped.content).toBe(true);
    console.log(
      `Chat smoke verified session ${session}; completed reply ${first.assistantMessageId}; canceled reply ${stopping.assistantMessageId}`,
    );
  } catch {
    // Never retain Playwright call logs, credentials, prompts or provider content.
    failure = `Staging Chat smoke failed during ${phase}; session ${session ?? "not created"}; reply ${reply ?? "not accepted"}`;
  } finally {
    if (session) {
      let cleanupPhase = "active reply cleanup";
      try {
        // Query our owned session even if acceptance parsing/UI assertions failed.
        // This also catches a reservation whose response was lost to the browser.
        const activeReplies = (await history()).filter((item) => item.status === "RUNNING");
        if (activeReplies.some((active) => active.role !== "ASSISTANT" || !uuid.test(active.id))) {
          failure = `${failure ?? "Chat smoke completed"}; invalid active reply in owned session ${session}`;
        } else {
          for (const active of activeReplies) {
            await api(
              `/api/chat/sessions/${session}/messages/${active.id}/cancel`,
              "POST",
              undefined,
              202,
            );
            await terminal(active.id);
          }
          cleanupPhase = "session deletion";
          await api(`/api/chat/sessions/${session}`, "DELETE", undefined, 204);
          console.log(`Smoke-owned Chat session deleted: ${session}`);
        }
      } catch {
        failure = `${failure ?? "Chat smoke completed"}; ${cleanupPhase} not confirmed for owned session ${session}`;
      }
    }
  }
  if (failure) throw new Error(failure);
});
