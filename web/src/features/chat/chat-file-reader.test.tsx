import { useState, type ReactNode } from "react";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import { ChatFileReader } from "./chat-file-reader";
import { ChatFilePicker } from "./chat-file-picker";
import { DocumentPreviewContent } from "../search/document-preview-content";
import { AssistantRuntimeProvider, ComposerPrimitive, useLocalRuntime } from "@assistant-ui/react";
import { ChatSourcesWorkspace } from "./chat-sources";
import { ChatFilePart } from "./chat-attachments";
import { fileReference } from "./chat-files";
import { i18n } from "@/i18n";

const backend = vi.hoisted(() => ({
  get: vi.fn(),
  read: vi.fn(),
  download: vi.fn(),
  list: vi.fn(),
  remove: vi.fn(),
  passages: vi.fn(),
  searchDocument: vi.fn(),
}));
vi.mock("@/lib/hey-api/sdk.gen", () => ({
  getChatFile: backend.get,
  readChatFilePassages: backend.passages,
  getSearchDocument: backend.searchDocument,
  readChatFileText: backend.read,
  downloadChatFile: backend.download,
  listChatFiles: backend.list,
  deleteChatFile: backend.remove,
  retryChatFile: vi.fn(),
  finalizeChatFileUpload: vi.fn(),
  getChatFilePolicy: vi.fn(),
  initiateChatFileUpload: vi.fn(),
}));
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
      <ApplicationSessionContext.Provider value={session}>
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </ApplicationSessionContext.Provider>,
    ),
    client,
  };
}

beforeEach(() => {
  vi.resetAllMocks();
  // jsdom has no layout/scroll implementation; the real browser covers scrolling.
  Element.prototype.scrollIntoView = vi.fn();
  backend.get.mockResolvedValue({ data: file });
  backend.list.mockResolvedValue({ data: [file] });
});
afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(Element.prototype, "scrollIntoView");
});

describe("Private file reader", () => {
  for (const mobile of [false, true]) {
    it(`opens message files in the shared ${mobile ? "mobile dialog" : "desktop panel"} without losing the composer draft`, async () => {
      vi.stubGlobal(
        "matchMedia",
        vi.fn(() => ({
          matches: !mobile,
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
        })),
      );
      backend.read.mockResolvedValue({
        data: { text: "Private document content", offset: 0, nextOffset: 24, totalCharacters: 24 },
      });
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
      await user.click(trigger);
      expect(await screen.findByText("Private document content")).toBeVisible();
      expect(screen.getByRole(mobile ? "dialog" : "complementary")).toBeVisible();
      expect(trigger).toHaveAttribute("aria-expanded", "true");
      await act(async () => {
        await i18n.changeLanguage("vi");
      });
      expect(screen.getByRole("button", { name: "Đóng tệp" })).toBeVisible();
      expect(backend.read).toHaveBeenCalledTimes(1);
      await user.keyboard("{Escape}");
      expect(screen.queryByRole(mobile ? "dialog" : "complementary")).not.toBeInTheDocument();
      expect(trigger).toHaveFocus();
      expect(screen.getByRole("textbox", { name: "Draft" })).toBe(draft);
      expect(draft).toHaveValue("Keep my draft");
      await waitFor(() => expect(view.client.getQueryCache().getAll()).toHaveLength(0));
      await act(async () => {
        await i18n.changeLanguage("en");
      });
    });
  }
  it("uses the private indexed reader and highlights the cited passage", async () => {
    await i18n.changeLanguage("vi");
    backend.passages.mockResolvedValue({
      data: {
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
      },
    });
    mount(
      <DocumentPreviewContent
        fileId={id}
        variant="chat"
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
    expect(backend.passages).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { fileId: id },
        query: { generation: missing2, from: 5 },
      }),
    );
    expect(backend.searchDocument).not.toHaveBeenCalled();
  });

  it("opens a cited Unicode window and highlights only the supplied range", async () => {
    backend.read.mockResolvedValue({
      data: { text: "😀đúng đoạn sau", offset: 20000, nextOffset: 20014, totalCharacters: 30000 },
    });
    const view = mount(<ChatFileReader fileId={id} initialOffset={20000} citationCount={10} />);
    await waitFor(() =>
      expect(view.container.querySelector("mark")).toHaveTextContent("😀đúng đoạn"),
    );
    expect(backend.read.mock.calls[0]?.[0].query).toEqual({ offset: 20000, count: 16000 });
  });
  it("renders extraction as inert text and uses the server Unicode cursor for the next window", async () => {
    backend.read
      .mockResolvedValueOnce({
        data: {
          text: "<script>alert(1)</script>😀",
          offset: 0,
          nextOffset: 25,
          totalCharacters: 30,
        },
      })
      .mockResolvedValueOnce({
        data: { text: "Phần cuối", offset: 25, nextOffset: 30, totalCharacters: 30 },
      });
    const view = mount(<ChatFileReader fileId={id} />);
    expect(await screen.findByText("<script>alert(1)</script>😀")).toBeInTheDocument();
    expect(view.container.querySelector("script")).toBeNull();
    expect(screen.getByRole("link", { name: "Download original" })).toHaveAttribute(
      "href",
      `/api/chat/files/${id}/content`,
    );
    await userEvent.click(screen.getByRole("button", { name: "Next part" }));
    expect(await screen.findByText("Phần cuối")).toBeInTheDocument();
    expect(backend.read.mock.calls[1]?.[0].query).toEqual({ offset: 25, count: 16000 });
    expect(screen.getByRole("button", { name: "Next part" })).toBeDisabled();
    view.unmount();
    await waitFor(() => expect(view.client.getQueryCache().getAll()).toHaveLength(0));
  });

  it("does not fetch content or offer download for an unavailable file", async () => {
    backend.get.mockRejectedValue(new Error("404"));
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
    backend.get.mockResolvedValue({ data: { ...file, mediaType: "image/png" } });
    backend.download.mockResolvedValue({ data: new Blob(["test"]) });
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
    backend.get.mockResolvedValue({ response: new Response(null, { status: 404 }) });
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
    await userEvent.click((await screen.findAllByRole("button", { name: "Gỡ" }))[0]!);
    expect(changed).toHaveBeenLastCalledWith([missing2, id]);
  });

  it("requires the shared confirmation dialog before deleting and retains failures for retry", async () => {
    backend.remove.mockRejectedValueOnce(new Error("File referenced"));
    mount(<ChatFilePicker selected={[]} onSelect={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: "Đính kèm tệp" }));
    await userEvent.click(screen.getByRole("button", { name: "Tất cả tệp gần đây" }));
    await userEvent.click(await screen.findByRole("button", { name: "Xóa" }));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(backend.remove).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Xóa tệp" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Không xóa được");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });
  it("keeps the composer compact and exposes three recent files through a popover", async () => {
    backend.list.mockResolvedValue({
      data: [
        file,
        { ...file, id: missing, filename: "Hai.txt" },
        { ...file, id: missing2, filename: "Ba.txt" },
        { ...file, id: "3fdc54bf-778f-4a21-bd96-e08f53a12c3d", filename: "Bốn.txt" },
      ],
    });
    const selected = vi.fn();
    mount(<ChatFilePicker selected={[]} onSelect={selected} />);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Đính kèm tệp" }));
    await screen.findByText("Ba.txt");
    expect(screen.getAllByRole("checkbox")).toHaveLength(3);
    expect(screen.queryByText("Bốn.txt")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Làm mới" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("checkbox", { name: file.filename }));
    expect(selected).toHaveBeenCalledWith([id], [file]);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Đính kèm tệp" })).toHaveFocus();
  });
});
