import { render as renderElement, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement } from "react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { handleGetChatWebAvailability } from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { ChatWebModes, ChatWebToggle } from "./chat-web-options";
import { sourceSchema } from "@/features/chat/sources/chat-evidence";

const state = {
  availability: {
    searchAvailable: true,
    automaticModelIds: ["a", "b"],
    nativeModelIds: [] as string[],
    inheritedModelId: "a",
  },
};

/** Renders with the availability the test set, answered once it is read. */
async function render(element: ReactElement) {
  server.use(handleGetChatWebAvailability({ body: structuredClone(state.availability) }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = renderElement(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
  await vi.waitFor(() =>
    expect(client.getQueryCache().find({ queryKey: [], exact: false })?.state.status).toBe(
      "success",
    ),
  );
  return {
    ...view,
    rerender: (next: ReactElement) =>
      view.rerender(<QueryClientProvider client={client}>{next}</QueryClientProvider>),
  };
}

vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1, capabilities: [] }),
}));
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
beforeEach(() => {
  state.availability.searchAvailable = true;
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  Element.prototype.scrollIntoView = vi.fn();
});

it("keeps Web off when no search connection is configured", async () => {
  state.availability.searchAvailable = false;
  const change = vi.fn();
  await render(<ChatWebModes value="off" onChange={change} onDone={vi.fn()} onBack={vi.fn()} />);
  expect(await screen.findByText("No search engine connected.")).toBeInTheDocument();
  const auto = screen.getByRole("radio", { name: "Use Web automatically" });
  expect(auto).toBeDisabled();
  await userEvent.click(auto);
  expect(change).not.toHaveBeenCalled();
});

it("lets a declared native-search model use Web without an external connection", async () => {
  state.availability.searchAvailable = false;
  state.availability.nativeModelIds = ["native"];
  try {
    const change = vi.fn();
    const done = vi.fn();
    await render(
      <ChatWebModes
        value="off"
        modelId="native"
        onChange={change}
        onDone={done}
        onBack={vi.fn()}
      />,
    );
    expect(screen.queryByText("No search engine connected.")).not.toBeInTheDocument();
    const auto = screen.getByRole("radio", { name: "Use Web automatically" });
    expect(auto).toBeEnabled();
    await userEvent.click(auto);
    expect(change).toHaveBeenCalledExactlyOnceWith("auto");
    expect(done).toHaveBeenCalledOnce();
  } finally {
    state.availability.nativeModelIds = [];
  }
});

it("offers Web to a tool-capable model only, never guessing from its name", async () => {
  const change = vi.fn();
  const { unmount } = await render(
    <ChatWebModes value="off" modelId="c" onChange={change} onDone={vi.fn()} onBack={vi.fn()} />,
  );
  expect(screen.getByRole("radio", { name: "Use Web automatically" })).toBeDisabled();
  expect(screen.getByRole("radio", { name: "Web off" })).toHaveAttribute("aria-checked", "true");
  await userEvent.click(screen.getByRole("radio", { name: "Use Web automatically" }));
  expect(change).not.toHaveBeenCalled();
  unmount();
  await render(
    <ChatWebModes value="off" modelId="b" onChange={change} onDone={vi.fn()} onBack={vi.fn()} />,
  );
  await userEvent.click(screen.getByRole("radio", { name: "Use Web automatically" }));
  expect(change).toHaveBeenCalledExactlyOnceWith("auto");
});

it("restores URL evidence without inventing a document identity and rejects unsafe links", () => {
  const source = {
    citationId: 1,
    title: "Article",
    documentId: null,
    generation: null,
    fileId: null,
    fileLocation: null,
    startOrdinal: 0,
    endOrdinal: 0,
    provenance: [],
    web: {
      url: "https://example.com/news",
      excerpt: "Evidence",
      retrievedAt: "2026-09-13T00:00:00Z",
    },
  };
  expect(sourceSchema.parse(source)).toEqual(source);
  for (const url of ["javascript:alert(1)", "file:///secret", "https://user:secret@example.com"]) {
    expect(sourceSchema.safeParse({ ...source, web: { ...source.web, url } }).success).toBe(false);
  }
  expect(sourceSchema.safeParse({ ...source, documentId: crypto.randomUUID() }).success).toBe(
    false,
  );
});

it("enabling Web from the menu row allows a search without forcing one, and keeps its options reachable", async () => {
  const change = vi.fn();
  const configure = vi.fn();
  const done = vi.fn();
  const { rerender } = await render(
    <ChatWebToggle value="off" onChange={change} onDone={done} onConfigure={configure} />,
  );
  const toggle = screen.getByRole("button", { name: "Web search" });
  expect(toggle).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(toggle);
  expect(change).toHaveBeenCalledExactlyOnceWith("auto");
  expect(done).toHaveBeenCalledOnce();
  await userEvent.click(screen.getByRole("button", { name: "Web options" }));
  expect(configure).toHaveBeenCalledOnce();
  rerender(<ChatWebToggle value="auto" onChange={change} onDone={done} onConfigure={configure} />);
  expect(screen.getByRole("button", { name: "Web search" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
});
