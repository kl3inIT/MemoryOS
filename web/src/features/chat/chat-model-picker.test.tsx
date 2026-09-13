import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { ChatModelPicker } from "./chat-model-picker";

const catalog = vi.hoisted(() => ({
  data: [
    {
      id: "luna",
      modelName: "gpt-5.6-luna",
      displayName: "GPT-5.6 Luna",
      providerName: "Deployment OpenAI",
      contextWindow: 36096,
      isDefault: true,
    },
    {
      id: "mini",
      modelName: "gpt-5-mini",
      displayName: "GPT-5 mini",
      providerName: "Deployment OpenAI",
      contextWindow: 32000,
      isDefault: false,
    },
  ],
}));
vi.mock("@tanstack/react-query", () => ({
  useQuery: () => ({ ...catalog, isPending: false, isError: false }),
}));
vi.mock("@/features/identity/application-session-context", () => ({
  useApplicationSession: () => ({ actorId: "actor", authorizationVersion: 1 }),
}));

beforeEach(() => {
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

it("shows the real inherited model and selection without context or deployment headings", async () => {
  const onChange = vi.fn();
  render(<ChatModelPicker onChange={onChange} disabled={false} />);
  const picker = screen.getByRole("combobox", { name: "Select model" });
  expect(picker).toHaveTextContent("GPT-5.6 Luna");
  await userEvent.click(picker);
  expect(screen.queryByText("Deployment OpenAI")).not.toBeInTheDocument();
  expect(screen.queryByText("Auto", { exact: true })).not.toBeInTheDocument();
  expect(screen.queryByText(/Context:/)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("option", { name: /GPT-5 mini/ }));
  expect(onChange).toHaveBeenCalledExactlyOnceWith("mini");
});

it("keeps an explicit selection instead of replacing it with the inherited default", () => {
  render(<ChatModelPicker value="mini" onChange={vi.fn()} disabled={false} />);
  expect(screen.getByRole("combobox", { name: "Select model" })).toHaveTextContent("GPT-5 mini");
});

it("does not present Luna when the explicit model is unavailable", () => {
  render(<ChatModelPicker value="removed" onChange={vi.fn()} disabled={false} />);
  expect(screen.getByRole("combobox", { name: "Select model" })).not.toHaveTextContent("Luna");
});
