import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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
import { createChatAttachmentAdapter, fileReference, uploadChatFile } from "./chat-files";
import { ChatComposerRoot, ChatComposerSend } from "./chat-composer";
import { ChatMessageAttachment } from "./chat-attachments";

const backend = vi.hoisted(() => ({
  policy: vi.fn(),
  initiate: vi.fn(),
  finalize: vi.fn(),
  get: vi.fn(),
  put: vi.fn(),
  hash: vi.fn(),
}));
vi.mock("@/lib/hey-api/sdk.gen", () => ({
  getChatFilePolicy: backend.policy,
  initiateChatFileUpload: backend.initiate,
  finalizeChatFileUpload: backend.finalize,
  getChatFile: backend.get,
}));
vi.mock("@/features/sources/direct-upload", () => ({
  putAuthorizedObject: backend.put,
  sha256: backend.hash,
}));

const id = "3fdc54bf-778f-4a21-bd96-e08f53a12c3a";
const ready = { id, filename: "Bản vẽ.png", mediaType: "image/png", sizeBytes: 3, status: "READY" };
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

describe("Chat attachments with assistant-ui runtime", () => {
  beforeEach(() => {
    class PreviewURL extends URL {
      static override createObjectURL = vi.fn(() => "blob:attachment-preview");
      static override revokeObjectURL = vi.fn();
    }
    vi.stubGlobal("URL", PreviewURL);
    backend.policy.mockResolvedValue({ data: { maxSizeBytes: 104857600 } });
    backend.hash.mockResolvedValue("a".repeat(64));
    backend.initiate.mockResolvedValue({
      data: {
        file: { ...ready, status: "UPLOADING" },
        upload: {
          method: "PUT",
          uri: "https://storage.example.test/upload",
          requiredHeaders: {},
          expiresAt: "2026-09-13T00:00:00Z",
        },
      },
    });
    backend.put.mockResolvedValue(undefined);
    backend.finalize.mockResolvedValue({ data: { ...ready, status: "PROCESSING" } });
    backend.get.mockResolvedValue({ data: ready });
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
    const input =
      container.querySelector<HTMLInputElement>('input[type="file"]') ??
      document.querySelector<HTMLInputElement>('input[type="file"]');
    expect(input).not.toBeNull();
    fireEvent.change(input!, {
      target: { files: [new File(["png"], ready.filename, { type: "image/png" })] },
    });
    await screen.findByText("Sẵn sàng");
    await user.click(screen.getByRole("button", { name: "Ảnh đính kèm" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Xem trước ảnh" })).toHaveAttribute(
      "src",
      "blob:attachment-preview",
    );
    await user.click(screen.getByRole("button", { name: "Đóng xem trước" }));
    await user.click(screen.getByRole("button", { name: "Gỡ tệp" }));
    await waitFor(() => expect(screen.queryByText("Sẵn sàng")).not.toBeInTheDocument());
    unmount();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:attachment-preview");
    expect(error).not.toHaveBeenCalled();
  });

  it("lets the native composer enable send only after worker readiness", async () => {
    const poll = Promise.withResolvers<{ data: typeof ready }>();
    backend.get.mockReturnValue(poll.promise);
    const { container } = render(<Harness adapter={createChatAttachmentAdapter(vi.fn())} />);
    await userEvent.click(screen.getByRole("button", { name: "Chọn tệp" }));
    const input =
      container.querySelector<HTMLInputElement>('input[type="file"]') ??
      document.querySelector<HTMLInputElement>('input[type="file"]');
    expect(input).not.toBeNull();
    fireEvent.change(input!, {
      target: { files: [new File(["png"], ready.filename, { type: "image/png" })] },
    });
    await waitFor(() => expect(backend.get).toHaveBeenCalled());
    expect(screen.getByRole("button", { name: "Gửi" })).toBeDisabled();
    await userEvent.type(screen.getByRole("textbox", { name: "Câu hỏi" }), "Đọc ảnh{Enter}");
    expect(screen.getByRole("textbox", { name: "Câu hỏi" })).toHaveValue("Đọc ảnh");
    expect(screen.getByText("Đang xử lý…")).toBeInTheDocument();
    await act(async () => poll.resolve({ data: ready }));
    await screen.findByText("Sẵn sàng");
    expect(screen.getByRole("button", { name: "Gửi" })).toBeEnabled();
    await userEvent.click(screen.getByRole("button", { name: "Gửi" }));
    expect(await screen.findByRole("button", { name: ready.filename })).toBeInTheDocument();
  });

  it("keeps the attachment running during processing and sends only the server file identity", async () => {
    const poll = Promise.withResolvers<{ data: typeof ready }>();
    backend.get.mockReturnValue(poll.promise);
    const adapter = createChatAttachmentAdapter(vi.fn());
    const updates = adapter.add({ file: new File(["png"], ready.filename, { type: "image/png" }) });
    if (!(Symbol.asyncIterator in updates)) throw new Error("Expected progress updates");
    const received: PendingAttachment[] = [];
    const completion = (async () => {
      for await (const update of updates) received.push(update);
    })();
    await waitFor(() => expect(backend.get).toHaveBeenCalled());
    expect(received.at(-1)?.status.type).toBe("running");
    await act(async () => poll.resolve({ data: ready }));
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

  it("reuses request and file identities after lost initiate/finalize responses without uploading twice", async () => {
    backend.initiate.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    backend.finalize.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const requestId = crypto.randomUUID();
    const saved = await uploadChatFile(
      new File(["png"], ready.filename, { type: "image/png" }),
      requestId,
      new AbortController().signal,
      vi.fn(),
    );
    expect(saved.id).toBe(id);
    expect(backend.initiate).toHaveBeenCalledTimes(2);
    expect(backend.initiate.mock.calls.map(([request]) => request.body.requestId)).toEqual([
      requestId,
      requestId,
    ]);
    expect(backend.finalize).toHaveBeenCalledTimes(2);
    expect(backend.finalize.mock.calls.map(([request]) => request.path.fileId)).toEqual([id, id]);
    expect(backend.put).toHaveBeenCalledTimes(1);
  });

  it("does not retry denied or canceled uploads", async () => {
    const denied = { status: 403, code: "FORBIDDEN" };
    backend.initiate.mockRejectedValueOnce(denied);
    await expect(
      uploadChatFile(
        new File(["png"], ready.filename),
        crypto.randomUUID(),
        new AbortController().signal,
        vi.fn(),
      ),
    ).rejects.toBe(denied);
    expect(backend.initiate).toHaveBeenCalledTimes(1);
    const controller = new AbortController();
    backend.initiate.mockImplementationOnce(() => {
      controller.abort();
      throw new TypeError("Aborted fetch");
    });
    await expect(
      uploadChatFile(
        new File(["png"], ready.filename),
        crypto.randomUUID(),
        controller.signal,
        vi.fn(),
      ),
    ).rejects.toThrow("Aborted fetch");
    expect(backend.initiate).toHaveBeenCalledTimes(2);
  });
});
