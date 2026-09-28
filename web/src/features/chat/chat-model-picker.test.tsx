import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { handleListAvailableChatModels } from "@/lib/hey-api/msw.gen";
import type { AvailableModel } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { ChatModelPicker } from "./chat-model-picker";

function model(
  fields: Pick<
    AvailableModel,
    "id" | "modelName" | "displayName" | "providerName" | "contextWindow" | "isDefault"
  >,
): AvailableModel {
  return {
    providerId: fields.providerName,
    capabilities: { streaming: true, toolCalling: true, vision: false, reasoning: false },
    maxOutputTokens: null,
    pricing: null,
    ...fields,
  };
}

const catalog: AvailableModel[] = [
  model({
    id: "luna",
    modelName: "gpt-5.6-luna",
    displayName: "GPT-5.6 Luna",
    providerName: "Deployment OpenAI",
    contextWindow: 36096,
    isDefault: true,
  }),
  model({
    id: "mini",
    modelName: "gpt-5-mini",
    displayName: "GPT-5 mini",
    providerName: "Deployment OpenAI",
    contextWindow: 32000,
    isDefault: false,
  }),
];

function renderPicker(props: { value?: string; onChange?: (id?: string) => void }) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ChatModelPicker onChange={props.onChange ?? vi.fn()} value={props.value} disabled={false} />
    </QueryClientProvider>,
  );
}

const picker = () => screen.findByRole("combobox", { name: "Select model" });

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
  server.use(handleListAvailableChatModels({ body: catalog }));
});

it("shows the real inherited model and selection without context or deployment headings", async () => {
  const onChange = vi.fn();
  renderPicker({ onChange });
  expect(await screen.findByText("GPT-5.6 Luna")).toBeInTheDocument();
  expect(await picker()).toHaveTextContent("GPT-5.6 Luna");
  await userEvent.click(await picker());
  expect(screen.queryByText("Deployment OpenAI")).not.toBeInTheDocument();
  expect(screen.queryByText("Auto", { exact: true })).not.toBeInTheDocument();
  expect(screen.queryByText(/Context:/)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("option", { name: /GPT-5 mini/ }));
  expect(onChange).toHaveBeenCalledExactlyOnceWith("mini");
});

it("keeps an explicit selection instead of replacing it with the inherited default", async () => {
  renderPicker({ value: "mini" });
  expect(await screen.findByText("GPT-5 mini")).toBeInTheDocument();
  expect(await picker()).toHaveTextContent("GPT-5 mini");
});

it("does not present Luna when the explicit model is unavailable", async () => {
  renderPicker({ value: "removed" });
  expect(await screen.findByText("The selected model is unavailable")).toBeInTheDocument();
  expect(await picker()).not.toHaveTextContent("Luna");
});

it("groups models under their provider once there is more than one, as Onyx", async () => {
  server.use(
    handleListAvailableChatModels({
      body: [
        ...catalog,
        model({
          id: "deepseek",
          modelName: "ocg/deepseek-v4-flash",
          displayName: "ocg/deepseek-v4-flash",
          providerName: "9Router",
          contextWindow: 1_000_000,
          isDefault: false,
        }),
      ],
    }),
  );
  renderPicker({});
  await screen.findByText("GPT-5.6 Luna");
  await userEvent.click(await picker());
  const headings = screen
    .getAllByText(/^(9Router|Deployment OpenAI)$/)
    .map((node) => node.textContent);
  expect(headings).toEqual(["9Router", "Deployment OpenAI"]);
  expect(screen.getByRole("option", { name: /deepseek-v4-flash/ })).toBeInTheDocument();
});
