import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import { ActionNotifications } from "@/components/ui/action-notifications";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { DangerZoneSection } from "./danger-zone-section";

afterEach(() => vi.unstubAllGlobals());

it("deletes every owned chat only after confirmation", async () => {
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (request: Request) => {
      requests.push(request);
      return new Response(null, { status: 204 });
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
  expect(requests).toHaveLength(0);
  fireEvent.click(await screen.findByRole("button", { name: "Delete" }));
  await waitFor(() => expect(requests).toHaveLength(1));
  expect(requests[0].method).toBe("DELETE");
  expect(new URL(requests[0].url).pathname).toBe("/api/chat/sessions");
});
