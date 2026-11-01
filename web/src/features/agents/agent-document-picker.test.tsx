import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { uploadChatFile, waitForChatFile } from "@/features/library/files";
import { handleGetChatFile } from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { AgentDocumentPicker, type AgentDocuments } from "./agent-document-picker";

vi.mock("@/features/library/files", async (actual) => ({
  ...(await actual<typeof import("@/features/library/files")>()),
  uploadChatFile: vi.fn(),
  waitForChatFile: vi.fn(),
}));

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

afterEach(() => {
  vi.mocked(uploadChatFile).mockReset();
  vi.mocked(waitForChatFile).mockReset();
});

const fileId = "71000000-0000-4000-8000-000000000001";
const source = {
  id: "72000000-0000-4000-8000-000000000001",
  name: "Sổ tay kế toán",
  type: "GOOGLE_DRIVE",
};
const readyFile = {
  id: fileId,
  filename: "Quy trình kê khai.pdf",
  mediaType: "application/pdf",
  sizeBytes: 2048,
  status: "READY" as const,
};

function Harness({
  initial,
  searching = true,
  onValue,
}: {
  initial: AgentDocuments;
  searching?: boolean;
  onValue?: (value: AgentDocuments) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <AgentDocumentPicker
      sources={[source]}
      documentSets={[]}
      known={{ sources: [], documentSets: [] }}
      value={value}
      withFiles
      pending={false}
      failed={false}
      disabled={!searching}
      searchesEverything={
        searching && value.sourceIds.length === 0 && value.documentSetIds.length === 0
      }
      onRetry={() => {}}
      onChange={(next) => {
        setValue(next);
        onValue?.(next);
      }}
    />
  );
}

function renderPicker(props: Parameters<typeof Harness>[0]) {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={cache}>
      <Harness {...props} />
    </QueryClientProvider>,
  );
}

const none: AgentDocuments = { sourceIds: [], documentSetIds: [], fileIds: [] };

describe("the documents state line", () => {
  it("says the assistant searches every readable document when nothing narrows it", () => {
    renderPicker({ initial: none });
    expect(screen.getByText("Tìm trong mọi tài liệu được phép đọc")).toBeInTheDocument();
  });

  it("says files are read in addition when only files are attached", async () => {
    server.use(handleGetChatFile({ body: readyFile }));
    renderPicker({ initial: { ...none, fileIds: [fileId] } });
    expect(await screen.findByText("Quy trình kê khai.pdf")).toBeInTheDocument();
    expect(screen.getByText("Tìm thêm trong mọi tài liệu được phép đọc")).toBeInTheDocument();
  });

  it("is gone once a Source narrows the search or the switch is off", () => {
    const { unmount } = renderPicker({ initial: { ...none, sourceIds: [source.id] } });
    expect(screen.queryByText(/mọi tài liệu được phép đọc/)).not.toBeInTheDocument();
    unmount();
    renderPicker({ initial: none, searching: false });
    expect(screen.queryByText(/mọi tài liệu được phép đọc/)).not.toBeInTheDocument();
  });
});

describe("uploading from the editor", () => {
  it("shows the file as processing and attaches it once the library has it ready", async () => {
    const user = userEvent.setup();
    const ready = Promise.withResolvers<typeof readyFile>();
    vi.mocked(uploadChatFile).mockResolvedValue({ ...readyFile, status: "PROCESSING" });
    vi.mocked(waitForChatFile).mockReturnValue(ready.promise);
    server.use(handleGetChatFile({ body: readyFile }));
    const onValue = vi.fn();
    renderPicker({ initial: none, onValue });

    await user.click(screen.getByRole("button", { name: "Thêm tài liệu" }));
    expect(await screen.findByRole("option", { name: "Tải tệp lên" })).toBeInTheDocument();
    const file = new File(["nội dung"], "Quy trình kê khai.pdf", { type: "application/pdf" });
    await user.upload(screen.getByLabelText("Tải tệp lên"), file);

    expect(await screen.findByText("Đang xử lý")).toBeInTheDocument();
    expect(vi.mocked(uploadChatFile).mock.calls[0]?.[0]).toBe(file);
    expect(onValue).not.toHaveBeenCalled();

    ready.resolve(readyFile);
    await waitFor(() => expect(onValue).toHaveBeenCalledWith({ ...none, fileIds: [fileId] }));
    expect(screen.queryByText("Đang xử lý")).not.toBeInTheDocument();
    expect(await screen.findByText("Quy trình kê khai.pdf")).toBeInTheDocument();
  });

  it("keeps a failed upload in the list with its reason until it is dismissed", async () => {
    const user = userEvent.setup();
    vi.mocked(uploadChatFile).mockRejectedValue(new Error("network"));
    renderPicker({ initial: none });

    const file = new File(["x"], "Bảng giá.xlsx");
    await user.upload(screen.getByLabelText("Tải tệp lên"), file);

    expect(await screen.findByText("Bảng giá.xlsx")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Bỏ Bảng giá.xlsx" }));
    expect(screen.queryByText("Bảng giá.xlsx")).not.toBeInTheDocument();
  });
});
