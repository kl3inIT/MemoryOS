import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { handleRenameSource, handleUpdateSourceAccess } from "@/lib/hey-api/msw.gen";
import type { SourceSummary } from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { type SourceMetadataField, SourceMetadataDialog } from "./source-metadata-dialog";

const SOURCE: SourceSummary = {
  id: "0d1c4a4e-5b0e-4a53-9d1e-2f6f0b8f7a11",
  name: "Employee handbook",
  type: "FILE",
  access: "PUBLIC",
  status: "ACTIVE",
  pendingWork: false,
  documentCount: 3,
  lastSucceededAt: null,
  errorCode: null,
  managerActorId: null,
  managerName: null,
  permissions: {
    edit: true,
    delete: true,
    publish: true,
    manageConfiguration: true,
    removeItems: true,
  },
};

function renderDialog(field: SourceMetadataField, source: Partial<SourceSummary> = {}) {
  const onClose = vi.fn();
  const onSaved = vi.fn(async () => {});
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <SourceMetadataDialog
        source={{ ...SOURCE, ...source }}
        field={field}
        disabled={false}
        restoreFocusRef={createRef()}
        onClose={onClose}
        onSaved={onSaved}
      />
    </QueryClientProvider>,
  );
  return { onClose, onSaved };
}

describe("SourceMetadataDialog", () => {
  it("saves a trimmed new name, then closes and refreshes", async () => {
    const user = userEvent.setup();
    const renamed: unknown[] = [];
    server.use(
      handleRenameSource(async ({ request }) => {
        renamed.push(await request.json());
        return HttpResponse.json({ ...SOURCE, name: "Policies" });
      }),
    );
    const { onClose, onSaved } = renderDialog("name");

    const dialog = screen.getByRole("dialog", { name: "Rename source" });
    const save = within(dialog).getByRole("button", { name: "Save name" });
    expect(save).toBeDisabled();
    const name = within(dialog).getByRole("textbox", { name: "Source name" });
    await user.clear(name);
    await user.type(name, "  Policies ");
    await user.click(save);

    await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
    expect(renamed).toEqual([{ name: "Policies" }]);
    expect(onSaved).toHaveBeenCalledOnce();
  });

  it("offers only Public and Private for File Sources", async () => {
    const user = userEvent.setup();
    renderDialog("access");
    const dialog = screen.getByRole("dialog", { name: "Change visibility" });
    await user.click(within(dialog).getByRole("combobox", { name: "Visibility" }));
    expect(await screen.findAllByRole("option")).toHaveLength(2);
    expect(screen.queryByRole("option", { name: /sync permissions from source/i })).toBeNull();
  });

  it("offers source permissions for Google Drive Sources", async () => {
    const user = userEvent.setup();
    renderDialog("access", { type: "GOOGLE_DRIVE", access: "SYNC" });
    const dialog = screen.getByRole("dialog", { name: "Change visibility" });
    const visibility = within(dialog).getByRole("combobox", { name: "Visibility" });
    expect(visibility).toHaveTextContent("Sync permissions from source");
    await user.click(visibility);
    expect(await screen.findAllByRole("option")).toHaveLength(3);
    expect(screen.getByRole("option", { name: /sync permissions from source/i })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  it("saves the chosen access and keeps the dialog open with the error when saving fails", async () => {
    const user = userEvent.setup();
    const requested: unknown[] = [];
    server.use(
      handleUpdateSourceAccess(async ({ request }) => {
        requested.push(await request.json());
        return requested.length === 1
          ? HttpResponse.json(
              { type: "about:blank", title: "Unavailable", status: 503 },
              { status: 503 },
            )
          : HttpResponse.json({ ...SOURCE, access: "PRIVATE" });
      }),
    );
    const { onClose } = renderDialog("access");

    const dialog = screen.getByRole("dialog", { name: "Change visibility" });
    const save = within(dialog).getByRole("button", { name: "Save visibility" });
    expect(save).toBeDisabled();
    await user.click(within(dialog).getByRole("combobox", { name: "Visibility" }));
    await user.click(await screen.findByRole("option", { name: /specific groups/i }));
    await user.click(save);

    expect(await within(dialog).findByRole("alert")).toBeVisible();
    expect(onClose).not.toHaveBeenCalled();

    await user.click(save);
    await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
    expect(requested).toEqual([{ access: "PRIVATE" }, { access: "PRIVATE" }]);
  });
});
