import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  AssistantRuntimeProvider,
  ComposerPrimitive,
  MessagePrimitive,
  ThreadPrimitive,
  useLocalRuntime,
  type AttachmentAdapter,
  type PendingAttachment,
} from "@assistant-ui/react";
import { ComposerAttachments } from "@/components/assistant-ui/elements/attachment.aui";
import { EditMessage } from "@/components/assistant-ui/elements/edit-message";
import {
  createChatAttachmentAdapter,
  fileReference,
  uploadChatFile,
  chatAttachmentProblem,
} from "@/features/library/files";
import { ChatComposerRoot, ChatComposerSend } from "@/features/chat/composer/chat-composer";
import { i18n } from "@/i18n/index";
import {
  handleFinalizeChatFileUpload,
  handleGetChatFile,
  handleGetChatFilePolicy,
  handleInitiateChatFileUpload,
} from "@/lib/hey-api/msw.gen";
import type { InitiateChatFileUploadData } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatMessageAttachment } from "./chat-attachments";

// The object-storage PUT and the browser hash are not API calls; they stay stubbed.
const backend = vi.hoisted(() => ({ put: vi.fn(), hash: vi.fn() }));
vi.mock("@/lib/direct-upload", () => ({
  putAuthorizedObject: backend.put,
  sha256: backend.hash,
}));

const id = "3fdc54bf-778f-4a21-bd96-e08f53a12c3a";
const ready = {
  id,
  filename: "Bản vẽ.png",
  mediaType: "image/png",
  sizeBytes: 3,
  status: "READY" as const,
};

type Reply = () => Response | Promise<Response>;
/** What the upload endpoints received. */
let requests: { initiate: InitiateChatFileUploadData["body"][]; finalize: string[]; polls: number };
/** One-shot replies that replace the next answer of an endpoint. */
let replies: { initiate: Reply[]; finalize: Reply[] };
/** Holds the readiness poll until the test lets the Worker finish. */
let processing: Promise<void> | undefined;

function uploadEndpoints() {
  return [
    handleGetChatFilePolicy({ body: { maxSizeBytes: 104857600 } }),
    handleInitiateChatFileUpload(async ({ request }) => {
      requests.initiate.push(await request.clone().json());
      const reply = replies.initiate.shift();
      return reply
        ? reply()
        : HttpResponse.json({
            file: { ...ready, status: "UPLOADING" },
            upload: {
              method: "PUT",
              uri: "https://storage.example.test/upload",
              requiredHeaders: {},
              expiresAt: "2026-09-13T00:00:00Z",
            },
          });
    }),
    handleFinalizeChatFileUpload(({ params }) => {
      requests.finalize.push(params.fileId);
      const reply = replies.finalize.shift();
      return reply ? reply() : HttpResponse.json({ ...ready, status: "PROCESSING" });
    }),
    handleGetChatFile(async () => {
      requests.polls++;
      await processing;
      return HttpResponse.json(ready);
    }),
  ];
}

function Harness({ adapter }: { adapter: AttachmentAdapter }) {
  const runtime = useLocalRuntime(
    { run: async () => ({ content: [] }) },
    { adapters: { attachments: adapter } },
  );
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ThreadPrimitive.Messages>
        {() => (
          <MessagePrimitive.Root>
            <MessagePrimitive.Attachments>
              {() => <ChatMessageAttachment readOnly={false} />}
            </MessagePrimitive.Attachments>
          </MessagePrimitive.Root>
        )}
      </ThreadPrimitive.Messages>
      <ChatComposerRoot>
        <ComposerAttachments />
        <ComposerPrimitive.AddAttachment>Chọn tệp</ComposerPrimitive.AddAttachment>
        <ComposerPrimitive.Input aria-label="Câu hỏi" />
        <ChatComposerSend>Gửi</ChatComposerSend>
      </ChatComposerRoot>
    </AssistantRuntimeProvider>
  );
}

function chooseFile(container: HTMLElement) {
  const input =
    container.querySelector<HTMLInputElement>('input[type="file"]') ??
    document.querySelector<HTMLInputElement>('input[type="file"]');
  expect(input).not.toBeNull();
  fireEvent.change(input!, {
    target: { files: [new File(["png"], ready.filename, { type: "image/png" })] },
  });
}

describe("Chat attachments with assistant-ui runtime", () => {
  beforeEach(() => {
    void i18n.changeLanguage("vi");
    class PreviewURL extends URL {
      static override createObjectURL = vi.fn(() => "blob:attachment-preview");
      static override revokeObjectURL = vi.fn();
    }
    vi.stubGlobal("URL", PreviewURL);
    backend.hash.mockResolvedValue("a".repeat(64));
    backend.put.mockResolvedValue(undefined);
    requests = { initiate: [], finalize: [], polls: 0 };
    replies = { initiate: [], finalize: [] };
    processing = undefined;
    server.use(...uploadEndpoints());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  it("previews an image through the upstream hook and releases object URLs when removed", async () => {
    const user = userEvent.setup();
    const error = vi.fn();
    const { container, unmount } = render(
      <StrictMode>
        <Harness adapter={createChatAttachmentAdapter(error)} />
      </StrictMode>,
    );
    await user.click(screen.getByRole("button", { name: "Chọn tệp" }));
    chooseFile(container);
    await waitFor(() => expect(screen.getByRole("button", { name: "Gửi" })).toBeEnabled());
    expect(screen.queryByText("Sẵn sàng")).not.toBeInTheDocument();
    const tile = screen.getByRole("button", { name: `Tệp đính kèm: ${ready.filename}` });
    await act(async () => {
      await i18n.changeLanguage("en");
    });
    expect(screen.getByRole("button", { name: `Attachment: ${ready.filename}` })).toBe(tile);
    expect(screen.getByRole("button", { name: "Remove file" })).toBeInTheDocument();
    await act(async () => {
      await i18n.changeLanguage("vi");
    });
    await user.click(screen.getByRole("button", { name: `Tệp đính kèm: ${ready.filename}` }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Xem trước ảnh" })).toHaveAttribute(
      "src",
      "blob:attachment-preview",
    );
    await user.click(screen.getByRole("button", { name: "Đóng xem trước" }));
    await user.click(screen.getByRole("button", { name: "Gỡ tệp" }));
    await waitFor(() =>
      expect(
        screen.queryByRole("button", { name: `Tệp đính kèm: ${ready.filename}` }),
      ).not.toBeInTheDocument(),
    );
    unmount();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:attachment-preview");
    expect(error).not.toHaveBeenCalled();
  });

  it("lets the native composer enable send only after worker readiness", async () => {
    const poll = Promise.withResolvers<void>();
    processing = poll.promise;
    const { container } = render(<Harness adapter={createChatAttachmentAdapter(vi.fn())} />);
    await userEvent.click(screen.getByRole("button", { name: "Chọn tệp" }));
    chooseFile(container);
    await waitFor(() => expect(requests.polls).toBeGreaterThan(0));
    expect(screen.getByRole("button", { name: "Gửi" })).toBeDisabled();
    await userEvent.type(screen.getByRole("textbox", { name: "Câu hỏi" }), "Đọc ảnh{Enter}");
    expect(screen.getByRole("textbox", { name: "Câu hỏi" })).toHaveValue("Đọc ảnh");
    expect(screen.getByText("Đang xử lý…")).toBeInTheDocument();
    await act(async () => poll.resolve());
    await waitFor(() => expect(screen.getByRole("button", { name: "Gửi" })).toBeEnabled());
    await userEvent.click(screen.getByRole("button", { name: "Gửi" }));
    expect(await screen.findByRole("button", { name: ready.filename })).toBeInTheDocument();
  });

  it("keeps the attachment running during processing and sends only the server file identity", async () => {
    const poll = Promise.withResolvers<void>();
    processing = poll.promise;
    const adapter = createChatAttachmentAdapter(vi.fn());
    const updates = adapter.add({ file: new File(["png"], ready.filename, { type: "image/png" }) });
    if (!(Symbol.asyncIterator in updates)) throw new Error("Expected progress updates");
    const received: PendingAttachment[] = [];
    const completion = (async () => {
      for await (const update of updates) received.push(update);
    })();
    await waitFor(() => expect(requests.polls).toBeGreaterThan(0));
    expect(received.at(-1)?.status.type).toBe("running");
    await act(async () => poll.resolve());
    await completion;
    const last = received.at(-1);
    if (!last) throw new Error("Missing attachment");
    const sent = await adapter.send(last);
    expect(sent.content).toEqual([
      { type: "file", filename: ready.filename, mimeType: "image/png", data: fileReference(id) },
    ]);
    expect(backend.put).toHaveBeenCalledTimes(1);
  });

  it("allows editing an attachment-only question but rejects an entirely empty message", async () => {
    const save = vi.fn();
    const props = {
      value: "",
      onValueChange: vi.fn(),
      onSave: save,
      onCancel: vi.fn(),
      pending: false,
    };
    const view = render(<EditMessage {...props} hasAttachments />);
    await userEvent.click(screen.getByRole("button", { name: "Lưu và gửi" }));
    expect(save).toHaveBeenCalledTimes(1);
    view.rerender(<EditMessage {...props} />);
    expect(screen.getByRole("button", { name: "Lưu và gửi" })).toBeDisabled();
  });

  // Fails today: the API client reports a lost response as an ApiError caused by the fetch TypeError, and
  // `retryLostResponse` in features/library/files.ts retries only a bare TypeError, so a lost response is never
  // retried. Drop `.fails` once the library recognises the wrapped network error.
  it("reuses request and file identities after lost initiate/finalize responses without uploading twice", async () => {
    replies.initiate.push(() => HttpResponse.error());
    replies.finalize.push(() => HttpResponse.error());
    const requestId = crypto.randomUUID();
    const saved = await uploadChatFile(
      new File(["png"], ready.filename, { type: "image/png" }),
      requestId,
      new AbortController().signal,
      vi.fn(),
    );
    expect(saved.id).toBe(id);
    expect(requests.initiate.map((request) => request.requestId)).toEqual([requestId, requestId]);
    expect(requests.finalize).toEqual([id, id]);
    expect(backend.put).toHaveBeenCalledTimes(1);
  });

  it("does not retry denied or canceled uploads", async () => {
    replies.initiate.push(() =>
      HttpResponse.json({ status: 403, code: "FORBIDDEN" }, { status: 403 }),
    );
    await expect(
      uploadChatFile(
        new File(["png"], ready.filename),
        crypto.randomUUID(),
        new AbortController().signal,
        vi.fn(),
      ),
    ).rejects.toMatchObject({ status: 403 });
    expect(requests.initiate).toHaveLength(1);
    const controller = new AbortController();
    replies.initiate.push(() => {
      controller.abort();
      return HttpResponse.error();
    });
    await expect(
      uploadChatFile(
        new File(["png"], ready.filename),
        crypto.randomUUID(),
        controller.signal,
        vi.fn(),
      ),
    ).rejects.toThrow();
    expect(requests.initiate).toHaveLength(2);
  });

  it("shares safe deferred upload errors between native composer and recent-file picker", async () => {
    expect(chatAttachmentProblem(new Error("private storage diagnostic"))).toEqual({
      key: "attachmentUpload",
    });
    const adapter = createChatAttachmentAdapter(vi.fn());
    replies.initiate.push(() =>
      HttpResponse.json({ detail: "private storage diagnostic" }, { status: 500 }),
    );
    const onError = vi.fn();
    const failed = createChatAttachmentAdapter(onError).add({ file: new File(["x"], "test.txt") });
    await expect(
      (async () => {
        if (Symbol.asyncIterator in failed) for await (const item of failed) void item;
      })(),
    ).rejects.toThrow();
    expect(onError).toHaveBeenCalledWith({ key: "attachmentUpload" });
    expect(requests.initiate).toHaveLength(1);
    adapter.cancelPending();
  });
});
