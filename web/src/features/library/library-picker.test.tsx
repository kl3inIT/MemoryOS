import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { handleListChatLibrary } from "@/lib/hey-api/msw.gen";
import type { ChatLibraryFile, ChatLibraryPage } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatLibraryPicker } from "./library-picker";

/** Every listing the picker asked for, as its query parameters. */
let listed: URLSearchParams[] = [];
const page = (items: ChatLibraryFile[]): ChatLibraryPage => ({
  items,
  totalCount: items.length,
  totalBytes: items.length * 1024,
  hasMore: false,
});
/** Answers each listing with `respond`, recording what it asked. */
function listing(respond: (params: URLSearchParams) => ChatLibraryPage | Promise<ChatLibraryPage>) {
  server.use(
    handleListChatLibrary(async ({ request }) => {
      const params = new URL(request.url).searchParams;
      listed.push(params);
      return HttpResponse.json(await respond(params));
    }),
  );
}

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
  listing(() => page([upload, image, attached]));
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
  listed = [];
});
afterEach(cleanup);

it("hands the chosen library files to the composer and closes", async () => {
  const { onAttach, onOpenChange } = show();
  const user = userEvent.setup();

  await user.click(await screen.findByRole("checkbox", { name: "Chọn hop-dong.pdf" }));
  await user.click(screen.getByRole("checkbox", { name: `Chọn ${image.filename}` }));
  await user.click(screen.getByRole("button", { name: "Đính kèm 2 tệp" }));

  expect(onAttach).toHaveBeenCalledWith([
    expect.objectContaining({ id: upload.id, source: "UPLOAD" }),
    expect.objectContaining({ id: image.id, source: "IMAGE" }),
  ]);
  // The copy belongs to the composer, where the wait is shown; the dialog does not hold the person (an
  // unanswered copy request would fail the test).
  expect(onOpenChange).toHaveBeenCalledWith(false);
});

it("shows what is already on the draft as attached and filters by source on the server", async () => {
  show(vi.fn(), [attached.id]);
  const user = userEvent.setup();

  const row = (await screen.findByText("da-gan.txt")).closest("label")!;
  expect(within(row).getByText("Đã đính kèm")).toBeInTheDocument();
  expect(within(row).getByRole("checkbox")).toBeDisabled();

  const narrowed = Promise.withResolvers<ChatLibraryPage>();
  listing((params) =>
    params.get("sources") === "IMAGE" ? narrowed.promise : page([upload, image, attached]),
  );
  await user.click(screen.getByRole("tab", { name: "Ảnh AI" }));
  await waitFor(() => expect(listed.at(-1)?.getAll("sources")).toEqual(["IMAGE"]));
  // The list being read stays on screen, marked busy, instead of emptying while the narrowed one loads.
  const list = screen.getByRole("list", { name: "Tệp trong thư viện" });
  await waitFor(() => expect(list).toHaveAttribute("aria-busy", "true"));
  expect(within(list).getByText("da-gan.txt")).toBeInTheDocument();

  narrowed.resolve(page([image]));
  await waitFor(() => expect(list).not.toHaveAttribute("aria-busy"));
  expect(within(list).queryByText("da-gan.txt")).not.toBeInTheDocument();
});

it("counts files the composer is still preparing against the message limit", async () => {
  listing(() => page([upload, image, attached]));
  render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <ChatLibraryPicker
        open
        onOpenChange={vi.fn()}
        selected={["55555555-5555-4555-8555-555555555555"]}
        preparing={3}
        onAttach={vi.fn()}
      />
    </QueryClientProvider>,
  );

  expect(await screen.findByText("Đã chọn 0/16 tệp")).toBeInTheDocument();
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

  await waitFor(() => expect(listed.at(-1)?.getAll("categories")).toEqual(["IMAGE"]));
});
