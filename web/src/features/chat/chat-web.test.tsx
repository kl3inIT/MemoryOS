import { render, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { WebSearch } from "@/components/assistant-ui/elements/web-search";
import { ChatWebModes, ChatWebToggle } from "./chat-web-options";
import { sourceSchema } from "./chat-evidence";

const state = vi.hoisted(() => ({
  availability: {
    searchAvailable: true,
    automaticModelIds: ["a", "b"],
    requiredModelIds: ["a"],
    nativeModelIds: [] as string[],
    inheritedModelId: "a",
  },
}));
vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({ data: state.availability, isPending: false, isError: false }),
}));
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

it("collapses completed Web results and keeps real status and links", async () => {
  const { rerender } = render(
    <WebSearch query="current news" results={[]} searching label="Searching the web…" />,
  );
  expect(screen.getByRole("status")).toHaveTextContent("Searching the web…");
  expect(screen.queryAllByRole("link")).toHaveLength(0);
  rerender(
    <WebSearch
      query="current news"
      results={[{ title: "Actual source", domain: "example.com", url: "https://example.com/news" }]}
      searching={false}
      label="1 web source"
    />,
  );
  expect(screen.queryAllByRole("link")).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "current news" }));
  expect(screen.getAllByRole("link")).toHaveLength(1);
  expect(screen.getByRole("link")).toHaveAttribute("href", "https://example.com/news");
  expect(screen.getByRole("link")).toHaveAttribute("rel", "noopener noreferrer");
  expect(screen.getByRole("status")).toHaveTextContent("1 web source");
});

it("keeps Web off when no search connection is configured", async () => {
  state.availability.searchAvailable = false;
  const change = vi.fn();
  render(<ChatWebModes value="off" onChange={change} onDone={vi.fn()} onBack={vi.fn()} />);
  expect(screen.getByText("No search engine connected.")).toBeInTheDocument();
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
    render(
      <ChatWebModes
        value="off"
        modelId="native"
        onChange={change}
        onDone={done}
        onBack={vi.fn()}
      />,
    );
    expect(screen.queryByText("No search engine connected.")).not.toBeInTheDocument();
    const required = screen.getByRole("radio", { name: "Require Web search" });
    expect(required).toBeEnabled();
    await userEvent.click(required);
    expect(change).toHaveBeenCalledExactlyOnceWith("required");
    expect(done).toHaveBeenCalledOnce();
  } finally {
    state.availability.nativeModelIds = [];
  }
});

it("uses adapter capabilities for required mode, never model-name guesses", async () => {
  const change = vi.fn();
  render(
    <ChatWebModes value="off" modelId="b" onChange={change} onDone={vi.fn()} onBack={vi.fn()} />,
  );
  expect(screen.getByRole("radio", { name: "Require Web search" })).toBeDisabled();
  expect(screen.getByRole("radio", { name: "Web off" })).toHaveAttribute("aria-checked", "true");
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

it("toggles Web for this turn from the menu row and keeps its options reachable", async () => {
  const change = vi.fn();
  const configure = vi.fn();
  const done = vi.fn();
  const { rerender } = render(
    <ChatWebToggle value="off" onChange={change} onDone={done} onConfigure={configure} />,
  );
  const toggle = screen.getByRole("button", { name: "Web search" });
  expect(toggle).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(toggle);
  expect(change).toHaveBeenCalledExactlyOnceWith("required");
  expect(done).toHaveBeenCalledOnce();
  await userEvent.click(screen.getByRole("button", { name: "Web options" }));
  expect(configure).toHaveBeenCalledOnce();
  rerender(<ChatWebToggle value="auto" onChange={change} onDone={done} onConfigure={configure} />);
  expect(screen.getByRole("button", { name: "Web search" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await userEvent.click(screen.getByRole("button", { name: "Web search" }));
  expect(change).toHaveBeenLastCalledWith("off");
});
