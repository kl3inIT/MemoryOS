import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { uploadChatFile } from "@/features/library/files";
import { AgentAvatar } from "./agent-avatar";
import { AgentAvatarStudio } from "./agent-avatar-studio";
import { agentFace } from "./agent-face";

vi.mock("@/features/library/files", async (original) => ({
  ...(await original<typeof import("@/features/library/files")>()),
  uploadChatFile: vi.fn(),
  waitForChatFile: vi.fn(),
}));

const agentId = "70000000-0000-4000-8000-000000000001";

beforeEach(async () => {
  vi.mocked(uploadChatFile).mockReset();
  await i18n.changeLanguage("vi");
});

function Harness({ saved = "kt-finance" }: { saved?: string }) {
  const [seed, setSeed] = useState(saved);
  const [fileId, setFileId] = useState<string | null>(null);
  return (
    <>
      <AgentAvatarStudio
        agentId={agentId}
        avatarSeed={seed}
        savedSeed={saved}
        hasAvatar={false}
        avatarFileId={fileId}
        allowImage
        disabled={false}
        onSeed={(value) => {
          setSeed(value);
          setFileId(null);
        }}
        onImage={setFileId}
        onClearImage={() => setFileId(null)}
      />
      <output data-testid="seed">{seed}</output>
      <output data-testid="image">{fileId ?? ""}</output>
    </>
  );
}

function mount() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Harness />
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

describe("AgentAvatar", () => {
  it("draws the same face for the same seed and shows an uploaded image first", () => {
    const { rerender } = render(
      <AgentAvatar agent={{ id: agentId, avatarSeed: "kt-legal", hasAvatar: false }} />,
    );
    const face = screen.getByRole("presentation");
    expect(face).toHaveAttribute("src", agentFace("kt-legal"));
    expect(agentFace("kt-legal")).toMatch(/^data:image\/svg\+xml/);
    expect(agentFace("kt-legal")).not.toBe(agentFace("kt-finance"));

    rerender(<AgentAvatar agent={{ id: agentId, avatarSeed: "kt-legal", hasAvatar: true }} />);
    expect(screen.getByRole("presentation")).toHaveAttribute(
      "src",
      `/api/chat/personas/${agentId}/avatar`,
    );
  });
});

describe("AgentAvatarStudio", () => {
  it("keeps the picked face and returns to the saved one with Mặc định", async () => {
    const user = mount();
    await user.click(screen.getByRole("button", { name: "Đổi ảnh đại diện" }));

    const faces = screen.getAllByRole("radio");
    expect(faces).toHaveLength(8);
    expect(faces[0]).toHaveAttribute("aria-checked", "true");
    const reset = screen.getByRole("button", { name: "Mặc định" });
    expect(reset).toBeDisabled();

    await user.click(screen.getByRole("radio", { name: "Khuôn mặt 3" }));
    const picked = screen.getByTestId("seed").textContent ?? "";
    expect(picked).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
    expect(picked).not.toBe("kt-finance");
    expect(screen.getByRole("radio", { name: "Khuôn mặt 3" })).toHaveAttribute(
      "aria-checked",
      "true",
    );

    await user.click(screen.getByRole("button", { name: "Đổi bộ khác" }));
    expect(screen.getAllByRole("radio")[0]).toHaveAttribute("aria-checked", "true");

    await user.click(reset);
    expect(screen.getByTestId("seed")).toHaveTextContent("kt-finance");
    expect(reset).toBeDisabled();
  });

  it("uploads a pasted image and uses it as the avatar", async () => {
    vi.mocked(uploadChatFile).mockResolvedValue({
      id: "80000000-0000-4000-8000-000000000001",
      status: "READY",
    } as Awaited<ReturnType<typeof uploadChatFile>>);
    const user = mount();
    await user.click(screen.getByRole("button", { name: "Đổi ảnh đại diện" }));

    const image = new File(["png"], "logo.png", { type: "image/png" });
    fireEvent.paste(screen.getByRole("dialog"), { clipboardData: { files: [image] } });

    await waitFor(() =>
      expect(screen.getByTestId("image")).toHaveTextContent(
        "80000000-0000-4000-8000-000000000001",
      ),
    );
    expect(vi.mocked(uploadChatFile).mock.calls[0]?.[0]).toBe(image);
  });

  it("refuses a pasted image over 2 MiB without uploading it", async () => {
    const user = mount();
    await user.click(screen.getByRole("button", { name: "Đổi ảnh đại diện" }));

    const image = new File([new Uint8Array(2 * 1024 * 1024 + 1)], "big.png", {
      type: "image/png",
    });
    fireEvent.paste(screen.getByRole("dialog"), { clipboardData: { files: [image] } });

    expect(await screen.findByText("Dùng ảnh PNG, JPEG, WebP hoặc GIF tối đa 2 MiB.")).toBeVisible();
    expect(uploadChatFile).not.toHaveBeenCalled();
  });
});
