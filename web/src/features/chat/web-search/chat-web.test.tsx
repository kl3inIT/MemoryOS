import { render as renderElement, screen, cleanup } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactElement } from "react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { handleGetChatWebAvailability } from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { ChatWebToggle } from "./chat-web-options";

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
  await render(<ChatWebToggle value="off" onChange={change} onDone={vi.fn()} />);
  expect(await screen.findByText("No search engine connected.")).toBeInTheDocument();
  const toggle = screen.getByRole("button", { name: "Web search" });
  expect(toggle).toBeDisabled();
  await userEvent.click(toggle);
  expect(change).not.toHaveBeenCalled();
});

it("lets a declared native-search model use Web without an external connection", async () => {
  state.availability.searchAvailable = false;
  state.availability.nativeModelIds = ["native"];
  try {
    const change = vi.fn();
    const done = vi.fn();
    await render(<ChatWebToggle value="off" modelId="native" onChange={change} onDone={done} />);
    expect(screen.queryByText("No search engine connected.")).not.toBeInTheDocument();
    const toggle = screen.getByRole("button", { name: "Web search" });
    expect(toggle).toBeEnabled();
    await userEvent.click(toggle);
    expect(change).toHaveBeenCalledExactlyOnceWith("auto");
    expect(done).toHaveBeenCalledOnce();
  } finally {
    state.availability.nativeModelIds = [];
  }
});

it("offers Web to a tool-capable model only, never guessing from its name", async () => {
  const change = vi.fn();
  const { unmount } = await render(
    <ChatWebToggle value="off" modelId="c" onChange={change} onDone={vi.fn()} />,
  );
  expect(screen.getByRole("button", { name: "Web search" })).toBeDisabled();
  expect(await screen.findByText("The selected model cannot use Web search.")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Web search" }));
  expect(change).not.toHaveBeenCalled();
  unmount();
  await render(<ChatWebToggle value="off" modelId="b" onChange={change} onDone={vi.fn()} />);
  expect(screen.queryByText("The selected model cannot use Web search.")).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Web search" }));
  expect(change).toHaveBeenCalledExactlyOnceWith("auto");
});

it("the Web row turns Web on and off by itself, with no submenu", async () => {
  const change = vi.fn();
  const done = vi.fn();
  const { rerender } = await render(<ChatWebToggle value="off" onChange={change} onDone={done} />);
  const toggle = screen.getByRole("button", { name: "Web search" });
  expect(toggle).toHaveAttribute("aria-pressed", "false");
  await userEvent.click(toggle);
  expect(change).toHaveBeenCalledExactlyOnceWith("auto");
  expect(done).toHaveBeenCalledOnce();
  expect(screen.getAllByRole("button")).toHaveLength(1);
  rerender(<ChatWebToggle value="auto" onChange={change} onDone={done} />);
  expect(screen.getByRole("button", { name: "Web search" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await userEvent.click(screen.getByRole("button", { name: "Web search" }));
  expect(change).toHaveBeenLastCalledWith("off");
});
