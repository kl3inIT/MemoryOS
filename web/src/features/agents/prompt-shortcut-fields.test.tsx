import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { beforeEach, expect, it } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { i18n } from "@/i18n/index";
import { handleCreateChatPromptShortcut } from "@/lib/hey-api/msw.gen";
import { server } from "@/test/msw";
import { ShortcutFields } from "./prompt-shortcut-fields";

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

function mount() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ActionNotifications>
        <ShortcutFields scope="own" />
        <button type="button">Elsewhere</button>
      </ActionNotifications>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

it("does not save a shortcut without content, and says why", async () => {
  const user = mount();
  const name = screen.getByRole("textbox", { name: "Tên lệnh tắt" });
  await user.type(name, "tom-tat");
  await user.click(screen.getByRole("button", { name: "Elsewhere" }));

  expect(await screen.findByText("Cần cả tên và nội dung.")).toBeVisible();
  expect(screen.getByRole("textbox", { name: "Nội dung lệnh tắt" })).toHaveAttribute(
    "aria-invalid",
    "true",
  );
});

it("shows the server's refusal under the pair", async () => {
  server.use(
    handleCreateChatPromptShortcut(() =>
      HttpResponse.json({ status: 409, title: "Conflict" }, { status: 409 }),
    ),
  );
  const user = mount();
  await user.type(screen.getByRole("textbox", { name: "Tên lệnh tắt" }), "tom-tat");
  await user.type(screen.getByRole("textbox", { name: "Nội dung lệnh tắt" }), "Tóm tắt giúp tôi");
  await user.click(screen.getByRole("button", { name: "Elsewhere" }));

  expect(await screen.findByRole("alert")).not.toBeEmptyDOMElement();
});
