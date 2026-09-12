import { useState, type ReactNode } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";
import { ChatFileReader } from "./chat-file-reader";
import { ChatFilePicker } from "./chat-file-picker";

const backend = vi.hoisted(() => ({
  get: vi.fn(),
  read: vi.fn(),
  download: vi.fn(),
  list: vi.fn(),
  remove: vi.fn(),
}));
vi.mock("@/lib/hey-api/sdk.gen", () => ({
  getChatFile: backend.get,
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
  backend.get.mockResolvedValue({ data: file });
  backend.list.mockResolvedValue({ data: [file] });
});
afterEach(() => vi.unstubAllGlobals());

describe("Private file reader", () => {
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
    expect(screen.getByRole("link", { name: "Tải bản gốc" })).toHaveAttribute(
      "href",
      `/api/chat/files/${id}/content`,
    );
    await userEvent.click(screen.getByRole("button", { name: "Phần tiếp" }));
    expect(await screen.findByText("Phần cuối")).toBeInTheDocument();
    expect(backend.read.mock.calls[1]?.[0].query).toEqual({ offset: 25, count: 16000 });
    expect(screen.getByRole("button", { name: "Phần tiếp" })).toBeDisabled();
    view.unmount();
    await waitFor(() => expect(view.client.getQueryCache().getAll()).toHaveLength(0));
  });

  it("does not fetch content or offer download for an unavailable file", async () => {
    backend.get.mockRejectedValue(new Error("404"));
    mount(<ChatFileReader fileId={id} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Không đọc được tệp");
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
    await userEvent.click(await screen.findByRole("checkbox", { name: file.filename }));
    expect(changed).toHaveBeenLastCalledWith([missing, missing2, id]);
    await userEvent.click((await screen.findAllByRole("button", { name: "Gỡ" }))[0]!);
    expect(changed).toHaveBeenLastCalledWith([missing2, id]);
  });

  it("requires the shared confirmation dialog before deleting and retains failures for retry", async () => {
    backend.remove.mockRejectedValueOnce(new Error("File referenced"));
    mount(<ChatFilePicker selected={[]} onSelect={vi.fn()} />);
    await userEvent.click(await screen.findByRole("button", { name: "Xóa" }));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(backend.remove).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Xóa tệp" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Không xóa được");
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });
});
