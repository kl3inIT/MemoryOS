import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import type { ChatLibraryFile } from "@/lib/hey-api/types.gen";
import { ChatLibraryPicker } from "./chat-library-picker";
import { ApiError } from "@/lib/api";

const listChatLibrary = vi.hoisted(() => vi.fn());
const copyChatLibraryFile = vi.hoisted(() => vi.fn());
const getChatFile = vi.hoisted(() => vi.fn());

vi.mock("@/lib/hey-api/sdk.gen", () => ({
  listChatLibrary: (...args: unknown[]) => listChatLibrary(...args),
  copyChatLibraryFile: (...args: unknown[]) => copyChatLibraryFile(...args),
  getChatFile: (...args: unknown[]) => getChatFile(...args),
}));

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));

const base: Pick<
  ChatLibraryFile,
  | "sizeBytes"
  | "createdAt"
  | "sessionId"
  | "sessionTitle"
  | "messageId"
  | "favorite"
  | "status"
  | "errorCode"
  | "deletedAt"
  | "purgeAfter"
  | "usedBy"
  | "deletable"
> = {
  sizeBytes: 1024,
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
const upload: ChatLibraryFile = {
  ...base,
  source: "UPLOAD",
  id: "11111111-1111-4111-8111-111111111111",
  filename: "hop-dong.pdf",
  mediaType: "application/pdf",
  category: "DOCUMENT",
};
const image: ChatLibraryFile = {
  ...base,
  source: "IMAGE",
  id: "22222222-2222-4222-8222-222222222222",
  filename: "image-20260921-101500-abcdef12.png",
  mediaType: "image/png",
  category: "IMAGE",
  sessionId: "33333333-3333-4333-8333-333333333333",
  sessionTitle: "Ảnh bìa",
};
const attached: ChatLibraryFile = {
  ...upload,
  id: "44444444-4444-4444-8444-444444444444",
  filename: "da-gan.txt",
};

function show(onAttach = vi.fn(), selected: string[] = []) {
  listChatLibrary.mockResolvedValue({
    data: { items: [upload, image, attached], totalCount: 3, totalBytes: 3072, hasMore: false },
  });
  const onOpenChange = vi.fn();
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatLibraryPicker open onOpenChange={onOpenChange} selected={selected} onAttach={onAttach} />
    </QueryClientProvider>,
  );
  return { onAttach, onOpenChange };
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  vi.clearAllMocks();
});
afterEach(cleanup);

it("attaches an upload as it is and a generated image through its ready copy", async () => {
  const { onAttach, onOpenChange } = show();
  const user = userEvent.setup();
  const copy = "55555555-5555-4555-8555-555555555555";
  copyChatLibraryFile.mockResolvedValue({
    data: {
      id: copy,
      filename: image.filename,
      mediaType: "image/png",
      sizeBytes: 1024,
      status: "PROCESSING",
    },
  });
  getChatFile.mockResolvedValue({
    data: {
      id: copy,
      filename: image.filename,
      mediaType: "image/png",
      sizeBytes: 1024,
      status: "READY",
    },
  });

  await user.click(await screen.findByRole("checkbox", { name: "Chọn hop-dong.pdf" }));
  await user.click(screen.getByRole("checkbox", { name: `Chọn ${image.filename}` }));
  await user.click(screen.getByRole("button", { name: "Đính kèm 2 tệp" }));

  await waitFor(() =>
    expect(onAttach).toHaveBeenCalledWith([
      expect.objectContaining({ id: upload.id, status: "READY" }),
      expect.objectContaining({ id: copy, status: "READY" }),
    ]),
  );
  expect(copyChatLibraryFile).toHaveBeenCalledWith(
    expect.objectContaining({ path: { source: "IMAGE", id: image.id } }),
  );
  expect(onOpenChange).toHaveBeenCalledWith(false);
});

it("shows what is already on the draft as attached and filters by source on the server", async () => {
  show(vi.fn(), [attached.id]);
  const user = userEvent.setup();

  const row = (await screen.findByText("da-gan.txt")).closest("label")!;
  expect(within(row).getByText("Đã đính kèm")).toBeInTheDocument();
  expect(within(row).getByRole("checkbox")).toBeDisabled();

  await user.click(screen.getByRole("tab", { name: "Ảnh AI" }));
  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ sources: ["IMAGE"] }) }),
    ),
  );
});

it("keeps the dialog open and says so when a copy fails", async () => {
  const { onAttach } = show();
  const user = userEvent.setup();
  copyChatLibraryFile.mockRejectedValue(new Error("gone"));

  await user.click(await screen.findByRole("checkbox", { name: `Chọn ${image.filename}` }));
  await user.click(screen.getByRole("button", { name: "Đính kèm 1 tệp" }));

  expect(
    await screen.findByText("Không chuẩn bị được tệp để đính kèm. Hãy thử lại."),
  ).toBeInTheDocument();
  expect(onAttach).not.toHaveBeenCalled();
});

it("names a full library instead of a generic retry when the copy is refused", async () => {
  const { onAttach } = show();
  const user = userEvent.setup();
  copyChatLibraryFile.mockRejectedValue(
    new ApiError(409, {
      code: "CHAT_STORAGE_FULL",
      usedBytes: 2147483648,
      limitBytes: 2147483648,
    }),
  );

  await user.click(await screen.findByRole("checkbox", { name: `Chọn ${image.filename}` }));
  await user.click(screen.getByRole("button", { name: "Đính kèm 1 tệp" }));

  expect(
    await screen.findByText("Thư viện tệp đã đầy. Xoá bớt tệp trong Thư viện rồi thử lại."),
  ).toBeInTheDocument();
  expect(
    screen.queryByText("Không chuẩn bị được tệp để đính kèm. Hãy thử lại."),
  ).not.toBeInTheDocument();
  expect(onAttach).not.toHaveBeenCalled();
});

it("names what it will attach and takes a file back off that list", async () => {
  show();
  const user = userEvent.setup();

  await user.click(await screen.findByRole("checkbox", { name: "Chọn hop-dong.pdf" }));
  const tray = screen.getByRole("list", { name: "Tệp sẽ đính kèm" });
  expect(within(tray).getByText("hop-dong.pdf")).toBeInTheDocument();

  await user.click(within(tray).getByRole("button", { name: "Bỏ chọn hop-dong.pdf" }));

  expect(screen.queryByRole("list", { name: "Tệp sẽ đính kèm" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Đính kèm 0 tệp" })).toBeDisabled();
});

it("narrows the library to one file kind from the filter", async () => {
  show();
  const user = userEvent.setup();

  await user.click(await screen.findByRole("button", { name: "Loại tệp" }));
  await user.click(await screen.findByRole("button", { name: "Ảnh" }));

  await waitFor(() =>
    expect(listChatLibrary).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ categories: ["IMAGE"] }) }),
    ),
  );
});
