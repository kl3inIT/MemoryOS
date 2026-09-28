import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { HttpResponse } from "msw";
import { expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { handleDeleteAllChatSessions } from "@/lib/hey-api/msw.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { server } from "@/test/msw";
import { DangerZoneSection } from "./danger-zone-section";

it("deletes every owned chat only after confirmation", async () => {
  const deleted = vi.fn();
  server.use(
    handleDeleteAllChatSessions(() => {
      deleted();
      return new HttpResponse(null, { status: 204 });
    }),
  );
  render(
    <QueryClientProvider client={createMemoryOsQueryClient()}>
      <ActionNotifications>
        <DangerZoneSection />
      </ActionNotifications>
    </QueryClientProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: "Delete All Chats" }));
  expect(deleted).not.toHaveBeenCalled();
  fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
  await waitFor(() => expect(deleted).toHaveBeenCalledOnce());
});
