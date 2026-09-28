import { useState, type ReactNode } from "react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { afterEach, assert, beforeEach, describe, expect, it, vi } from "vitest";
import {
  handleDeleteChatFile,
  handleDownloadChatFile,
  handleGetChatFile,
  handleGetSearchDocument,
  handleListChatFiles,
  handleReadChatDocumentPassages,
  handleReadChatFilePassages,
  handleReadChatFileText,
} from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import { ChatFileReader } from "./chat-file-reader";
import { ChatFilePicker } from "@/features/library/file-picker";
import { DocumentPreviewContent } from "@/features/documents/document-preview-content";
import { useDocumentReading } from "@/features/documents/document-reading";
import type { DocumentSelection } from "@/features/documents/document-preview-dialog";
import { AssistantRuntimeProvider, ComposerPrimitive, useLocalRuntime } from "@assistant-ui/react";
import { ChatSourcesWorkspace } from "./chat-sources";
import { ChatFilePart } from "@/features/chat/thread/chat-attachments";
import { fileReference } from "@/features/library/files";
import { i18n } from "@/i18n/index";

/**
 * The HTTP boundary as MSW handlers that delegate to one recorder per operation, so each test sets the answer and
 * reads the requests it cares about. A recorder left unset answers 501, which the code under test sees as a failure.
 */
type Reply = (request: {
  params: Record<string, string>;
  query: Record<string, string>;
}) => Response;
const notImplemented: Reply = () => new HttpResponse(null, { status: 501 });
const backend = {
  get: vi.fn<Reply>(),
  read: vi.fn<Reply>(),
  download: vi.fn<Reply>(),
  list: vi.fn<Reply>(),
  remove: vi.fn<Reply>(),
  passages: vi.fn<Reply>(),
  searchDocument: vi.fn<Reply>(),
};
function delegate(reply: typeof backend.get) {
  return ({ params, request }: { params: Record<string, unknown>; request: Request }) =>
    reply({
      params: Object.fromEntries(
        Object.entries(params).map(([key, value]) => [key, String(value)]),
      ),
      query: Object.fromEntries(new URL(request.url).searchParams),
    });
}
const id = "3fdc54bf-778f-4a21-bd96-e08f53a12c3a";
const missing = "3fdc54bf-778f-4a21-bd96-e08f53a12c3b";
const missing2 = "3fdc54bf-778f-4a21-bd96-e08f53a12c3c";
const file = {
  id,
  filename: "Ghi chú.txt",
  mediaType: "text/plain",
  sizeBytes: 4,
  status: "READY",
};
const session: ApplicationSession = {
  actorId: id,
  authorizationVersion: 1,
  uiLanguage: "en",
  capabilities: [],
  scopedCapabilities: [],
  tenant: { displayName: "Test", role: "MEMBER" },
};

function mount(children: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    ...render(
      <ApplicationSessionContext value={session}>
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </ApplicationSessionContext>,
    ),
    client,
  };
}

beforeEach(() => {
  for (const reply of Object.values(backend)) reply.mockReset().mockImplementation(notImplemented);
  // jsdom has no layout/scroll implementation; the real browser covers scrolling.
  Element.prototype.scrollIntoView = vi.fn();
  backend.get.mockImplementation(() => HttpResponse.json(file));
  backend.list.mockImplementation(() => HttpResponse.json([file]));
  server.use(
    handleGetChatFile(delegate(backend.get)),
    handleReadChatFileText(delegate(backend.read)),
    handleDownloadChatFile(delegate(backend.download)),
    handleListChatFiles(delegate(backend.list)),
    handleDeleteChatFile(delegate(backend.remove)),
    handleReadChatFilePassages(delegate(backend.passages)),
    handleReadChatDocumentPassages(delegate(backend.searchDocument)),
    handleGetSearchDocument(delegate(backend.searchDocument)),
  );
});
afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(Element.prototype, "scrollIntoView");
});

describe("Private file reader", () => {
  for (const mobile of [false, true]) {
    it(`opens message files in the centered preview modal on ${mobile ? "mobile" : "desktop"} without losing the composer draft`, async () => {
      vi.stubGlobal(
        "matchMedia",
        vi.fn(() => ({
          matches: !mobile,
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
        })),
      );
      // The attachment route serves octet-stream; the modal previews it by its stored type, as Onyx.
      backend.download.mockImplementation(
        () =>
          new HttpResponse("Private document content", {
            headers: { "Content-Type": "application/octet-stream" },
          }),
      );
      function Workspace() {
        const runtime = useLocalRuntime({ run: async () => ({ content: [] }) });
        return (
          <AssistantRuntimeProvider runtime={runtime}>
            <ChatSourcesWorkspace>
              <ChatFilePart
                data={fileReference(id)}
                filename={file.filename}
                mimeType={file.mediaType}
              />
              <ComposerPrimitive.Root>
                <ComposerPrimitive.Input aria-label="Draft" />
              </ComposerPrimitive.Root>
            </ChatSourcesWorkspace>
          </AssistantRuntimeProvider>
        );
      }
      const view = mount(<Workspace />);
      const user = userEvent.setup();
      const draft = screen.getByRole("textbox", { name: "Draft" });
      await user.type(draft, "Keep my draft");
      const trigger = screen.getByRole("button", { name: file.filename });
      expect(trigger).toHaveAttribute("aria-haspopup", "dialog");
      await user.click(trigger);
      // The viewer's code loads on first open, which takes longer than a render under test (and than the
      // default test timeout on a loaded machine, hence the test's own).
      const dialog = await screen.findByRole(
        "dialog",
        { name: file.filename },
        { timeout: 10_000 },
      );
      // Shiki replaces the plain fallback after it loads, so the text is re-queried rather than held.
      await waitFor(() =>
        expect(within(dialog).getByText("Private document content")).toBeVisible(),
      );
      expect(within(dialog).getByText("24 B · 1 lines")).toBeVisible();
      expect(within(dialog).getByRole("link", { name: "Download" })).toHaveAttribute(
        "href",
        `/api/chat/files/${id}/content`,
      );
      await act(async () => {
        await i18n.changeLanguage("vi");
      });
      expect(within(dialog).getByRole("button", { name: "Đóng xem trước" })).toBeVisible();
      expect(backend.download).toHaveBeenCalledTimes(1);
      expect(backend.read).not.toHaveBeenCalled();
      await user.keyboard("{Escape}");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(trigger).toHaveFocus();
      expect(screen.getByRole("textbox", { name: "Draft" })).toBe(draft);
      expect(draft).toHaveValue("Keep my draft");
      await waitFor(() => expect(view.client.getQueryCache().getAll()).toHaveLength(0));
      await act(async () => {
        await i18n.changeLanguage("en");
      });
    }, 15_000);
  }

  /** The dialog owns the reading state and hands it to both views; the test opens the same way. */
  function CitedPassages({ selection, fileId }: { selection: DocumentSelection; fileId: string }) {
    const reading = useDocumentReading(selection, "chat", fileId);
    return <DocumentPreviewContent variant="chat" selection={selection} reading={reading} />;
  }

  it("uses the private indexed reader and highlights the cited passage", async () => {
    await i18n.changeLanguage("vi");
    backend.passages.mockImplementation(() =>
      HttpResponse.json({
        documentId: missing,
        generation: missing2,
        title: "Ghi chú.txt",
        firstOrdinal: 5,
        totalChunks: 8,
        hasMore: false,
        passages: [
          { ordinal: 5, content: "Ngữ cảnh", provenanceJson: "{}" },
          { ordinal: 7, content: "Đoạn được trích dẫn", provenanceJson: "{}" },
        ],
      }),
    );
    mount(
      <CitedPassages
        fileId={id}
        selection={{
          documentId: id,
          generation: missing2,
          title: "Ghi chú.txt",
          activeMatchIndex: 0,
          matches: [{ from: 5, matchingOrdinal: 7 }],
        }}
      />,
    );
    expect(await screen.findByRole("article", { name: "Đoạn được chọn" })).toHaveTextContent(
      "Đoạn được trích dẫn",
    );
    expect(backend.passages).toHaveBeenCalledWith({
      params: expect.objectContaining({ fileId: id }),
      query: { generation: missing2, from: "5" },
    });
    expect(backend.searchDocument).not.toHaveBeenCalled();
  });

  it("opens a cited Unicode window and highlights only the supplied range", async () => {
    backend.read.mockImplementation(() =>
      HttpResponse.json({
        text: "😀đúng đoạn sau",
        offset: 20000,
        nextOffset: 20014,
        totalCharacters: 30000,
      }),
    );
    const view = mount(<ChatFileReader fileId={id} initialOffset={20000} citationCount={10} />);
    await waitFor(() =>
      expect(view.container.querySelector("mark")).toHaveTextContent("😀đúng đoạn"),
    );
    expect(backend.read.mock.calls[0]?.[0].query).toEqual({ offset: "20000", count: "16000" });
  });
  it("renders extraction as inert text and uses the server Unicode cursor for the next window", async () => {
    backend.read
      .mockImplementationOnce(() =>
        HttpResponse.json({
          text: "<script>alert(1)</script>😀",
          offset: 0,
          nextOffset: 25,
          totalCharacters: 30,
        }),
      )
      .mockImplementationOnce(() =>
        HttpResponse.json({ text: "Phần cuối", offset: 25, nextOffset: 30, totalCharacters: 30 }),
      );
    const view = mount(<ChatFileReader fileId={id} />);
    expect(await screen.findByText("<script>alert(1)</script>😀")).toBeInTheDocument();
    expect(view.container.querySelector("script")).toBeNull();
    expect(screen.getByRole("link", { name: "Download original" })).toHaveAttribute(
      "href",
      `/api/chat/files/${id}/content`,
    );
    await userEvent.click(screen.getByRole("button", { name: "Next part" }));
    expect(await screen.findByText("Phần cuối")).toBeInTheDocument();
    expect(backend.read.mock.calls[1]?.[0].query).toEqual({ offset: "25", count: "16000" });
    expect(screen.getByRole("button", { name: "Next part" })).toBeDisabled();
    view.unmount();
    await waitFor(() => expect(view.client.getQueryCache().getAll()).toHaveLength(0));
  });

  it("does not fetch content or offer download for an unavailable file", async () => {
    backend.get.mockImplementation(() => new HttpResponse(null, { status: 404 }));
    mount(<ChatFileReader fileId={id} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("File unavailable");
    expect(backend.read).not.toHaveBeenCalled();
    expect(backend.download).not.toHaveBeenCalled();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("reuses object URL cleanup for a saved image and releases it when the reader closes", async () => {
    class PreviewURL extends URL {
      static override createObjectURL = vi.fn(() => "blob:stored-image");
      static override revokeObjectURL = vi.fn();
    }
    vi.stubGlobal("URL", PreviewURL);
    backend.get.mockImplementation(() => HttpResponse.json({ ...file, mediaType: "image/png" }));
    backend.download.mockImplementation(() => new HttpResponse("test"));
    const view = mount(<ChatFileReader fileId={id} />);
    expect(await screen.findByRole("img", { name: file.filename })).toHaveAttribute(
      "src",
      "blob:stored-image",
    );
    expect(backend.read).not.toHaveBeenCalled();
    view.unmount();
    expect(PreviewURL.revokeObjectURL).toHaveBeenCalledWith("blob:stored-image");
  });
});

describe("File selection", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("vi");
  });
  it("retains missing identities when selecting another file or removing just one missing file", async () => {
    backend.get.mockImplementation(() => new HttpResponse(null, { status: 404 }));
    const changed = vi.fn();
    function Picker() {
      const [selected, setSelected] = useState([missing, missing2]);
      return (
        <ChatFilePicker
          selected={selected}
          onSelect={(ids) => {
            setSelected(ids);
            changed(ids);
          }}
        />
      );
    }
    mount(<Picker />);
    await userEvent.click(screen.getByRole("button", { name: "Đính kèm tệp" }));
    await userEvent.click(screen.getByRole("button", { name: "Tất cả tệp gần đây" }));
    await userEvent.click(await screen.findByRole("checkbox", { name: file.filename }));
    expect(changed).toHaveBeenLastCalledWith([missing, missing2, id]);
    const [remove] = await screen.findAllByRole("button", { name: "Gỡ" });
    assert.isDefined(remove);
    await userEvent.click(remove);
    expect(changed).toHaveBeenLastCalledWith([missing2, id]);
  });

  it("requires the shared confirmation dialog before deleting and retains failures for retry", async () => {
    backend.remove.mockImplementationOnce(() => new HttpResponse(null, { status: 409 }));
    mount(<ChatFilePicker selected={[]} onSelect={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: "Đính kèm tệp" }));
    await userEvent.click(screen.getByRole("button", { name: "Tất cả tệp gần đây" }));
    await userEvent.click(await screen.findByRole("button", { name: `Xóa ${file.filename}` }));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(backend.remove).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Xóa tệp" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Không xóa được");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });
  it("shows file status as labelled icons and keeps actionable failures readable", async () => {
    backend.list.mockImplementation(() =>
      HttpResponse.json([
        { ...file, status: "PROCESSING" },
        { ...file, id: missing, filename: "Lỗi.txt", status: "FAILED", errorCode: "PARSE" },
        { ...file, id: missing2, filename: "Chưa tìm được.txt", searchReady: false },
      ]),
    );
    mount(<ChatFilePicker selected={[]} onSelect={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: "Đính kèm tệp" }));
    expect(await screen.findByRole("img", { name: "Đang xử lý…" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Xử lý lỗi" })).toBeInTheDocument();
    expect(
      screen.getByRole("img", { name: "Đọc được · Chưa sẵn sàng tìm kiếm" }),
    ).toBeInTheDocument();
    // The compact list relies on icons; only the full dialog repeats the failure as text.
    expect(screen.queryByText("Xử lý lỗi")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Tất cả tệp gần đây" }));
    expect(await screen.findByText("Xử lý lỗi")).toBeInTheDocument();
    expect(screen.queryByText("Đang xử lý…")).not.toBeInTheDocument();
  });

  it("keeps the composer compact and exposes three recent files through a popover", async () => {
    backend.list.mockImplementation(() =>
      HttpResponse.json([
        file,
        { ...file, id: missing, filename: "Hai.txt" },
        { ...file, id: missing2, filename: "Ba.txt" },
        { ...file, id: "3fdc54bf-778f-4a21-bd96-e08f53a12c3d", filename: "Bốn.txt" },
      ]),
    );
    const selected = vi.fn();
    mount(<ChatFilePicker selected={[]} onSelect={selected} />);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Đính kèm tệp" }));
    await screen.findByText("Ba.txt");
    expect(screen.getAllByRole("checkbox")).toHaveLength(3);
    // Ready files carry no status label; only limiting states are shown.
    expect(screen.queryByText("Sẵn sàng")).not.toBeInTheDocument();
    expect(screen.queryByText("Bốn.txt")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Làm mới" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("checkbox", { name: file.filename }));
    expect(selected).toHaveBeenCalledWith([id], [file]);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Đính kèm tệp" })).toHaveFocus();
  });
});
