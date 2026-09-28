import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it } from "vitest";
import { SidebarProvider, useSidebar } from "./sidebar";

function State() {
  const { state } = useSidebar();
  return <output aria-label="Sidebar">{state}</output>;
}

// The sidebar remembers its state in local storage; each test starts expanded.
beforeEach(() => window.localStorage.clear());

function mount() {
  render(
    <SidebarProvider>
      <State />
      <input aria-label="Title" />
      <textarea aria-label="Body" />
      <div role="textbox" aria-label="Editor" contentEditable suppressContentEditableWarning>
        text
      </div>
    </SidebarProvider>,
  );
  return screen.getByRole("status", { name: "Sidebar" });
}

it("toggles with Ctrl+B outside text entry", async () => {
  const state = mount();
  await userEvent.keyboard("{Control>}b{/Control}");
  expect(state).toHaveTextContent("collapsed");
  await userEvent.keyboard("{Meta>}b{/Meta}");
  expect(state).toHaveTextContent("expanded");
});

it("leaves Ctrl+B to an input, a text area and an editor that has focus", async () => {
  const state = mount();
  for (const name of ["Title", "Body", "Editor"]) {
    await userEvent.click(screen.getByRole("textbox", { name }));
    await userEvent.keyboard("{Control>}b{/Control}");
    expect(state).toHaveTextContent("expanded");
  }
});

it("ignores Ctrl+Shift+B", async () => {
  const state = mount();
  await userEvent.keyboard("{Control>}{Shift>}b{/Shift}{/Control}");
  expect(state).toHaveTextContent("expanded");
});
