import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it } from "vitest";
import { AssistantRuntimeProvider, useLocalRuntime } from "@assistant-ui/react";
import { i18n } from "@/i18n/index";
import { HttpResponse } from "msw";
import { handleCopyChatLibraryFile, handleGetChatFile } from "@/lib/hey-api/msw.gen";
import type { ChatLibraryFile } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatAttachmentStaging, ChatComposerAttachments } from "./chat-attachment-staging";
import { useChatAttachmentStaging } from "./chat-attachment-staging-context";

const copyId = "55555555-5555-4555-8555-555555555555";
const generated: ChatLibraryFile = {
  source: "IMAGE",
  id: "22222222-2222-4222-8222-222222222222",
  filename: "bieu-do.png",
  mediaType: "image/png",
  category: "IMAGE",
  sizeBytes: 2048,
  createdAt: new Date().toISOString(),
  sessionId: null,
  sessionTitle: null,
  messageId: null,
  favorite: false,
  status: "READY",
  errorCode: null,
  deletedAt: null,
  purgeAfter: null,
  usedBy: [],
  deletable: true,
};

function Attach() {
  const staging = useChatAttachmentStaging();
  return (
    <button type="button" onClick={() => staging.attach([generated])}>
      Chọn tệp
    </button>
  );
}

function Harness() {
  const runtime = useLocalRuntime({ run: async () => ({ content: [] }) });
  return (
    <AssistantRuntimeProvider runtime={runtime}>
      <ChatAttachmentStaging>
        <ChatComposerAttachments />
        <Attach />
      </ChatAttachmentStaging>
    </AssistantRuntimeProvider>
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});
afterEach(cleanup);

it("waits for a library copy on the composer and turns it into an attachment", async () => {
  const user = userEvent.setup();
  const copy = Promise.withResolvers<void>();
  const copied = {
    id: copyId,
    filename: generated.filename,
    mediaType: "image/png",
    sizeBytes: 2048,
  };
  server.use(
    handleCopyChatLibraryFile(async () => {
      await copy.promise;
      return HttpResponse.json({ ...copied, status: "PROCESSING" });
    }),
    handleGetChatFile({ body: { ...copied, status: "READY" } }),
  );
  render(<Harness />);

  await user.click(screen.getByRole("button", { name: "Chọn tệp" }));

  expect(await screen.findByText(generated.filename)).toBeInTheDocument();
  expect(screen.getByText("Đang chuẩn bị…")).toBeInTheDocument();

  copy.resolve();

  expect(
    await screen.findByRole("button", { name: `Tệp đính kèm: ${generated.filename}` }),
  ).toBeInTheDocument();
  expect(screen.queryByText("Đang chuẩn bị…")).not.toBeInTheDocument();
});

it("keeps a failed copy on the composer until it is taken off", async () => {
  const user = userEvent.setup();
  server.use(handleCopyChatLibraryFile(() => HttpResponse.json({}, { status: 404 })));
  render(<Harness />);

  await user.click(screen.getByRole("button", { name: "Chọn tệp" }));

  expect(
    await screen.findByText("Không đính kèm được tệp. Hãy gỡ tệp rồi thử lại."),
  ).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Gỡ tệp" }));

  await waitFor(() => expect(screen.queryByText(generated.filename)).not.toBeInTheDocument());
});
