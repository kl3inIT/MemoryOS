import { render, screen } from "@testing-library/react";
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router";
import { describe, expect, it } from "vitest";
import { Button } from "@/components/ui/button";
import { DangerZone } from "./danger-zone";
import { DetailHeader } from "./detail-header";
import { EmptyState } from "./empty-state";

async function renderInRouter(element: React.ReactNode) {
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: "/",
    component: () => element,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  render(<RouterProvider router={router} />);
  await screen.findByRole("navigation", { name: "Breadcrumb" });
}

describe("detail page composites", () => {
  it("leads back to the list and names the resource once as a heading and once as the trail's end", async () => {
    await renderInRouter(
      <DetailHeader
        parent={{ label: "Nhóm", to: "/admin/groups" }}
        title="Kế toán"
        description="12 thành viên"
        actions={<Button>Lưu</Button>}
      />,
    );
    expect(screen.getByRole("link", { name: "Nhóm" })).toHaveAttribute("href", "/admin/groups");
    expect(screen.getByRole("heading", { name: "Kế toán" })).toBeVisible();
    // The trail ends on the current page: marked as current and not navigable.
    const current = screen.getByRole("link", { name: "Kế toán" });
    expect(current).toHaveAttribute("aria-current", "page");
    expect(current).not.toHaveAttribute("href");
    expect(screen.getByText("12 thành viên")).toBeVisible();
    expect(screen.getByRole("button", { name: "Lưu" })).toBeVisible();
  });

  it("offers a way forward when there is nothing to show", () => {
    render(
      <EmptyState
        title="Không còn nguồn này"
        detail="Có thể nguồn đã bị xoá."
        action={<Button>Thử lại</Button>}
      />,
    );
    expect(screen.getByRole("heading", { name: "Không còn nguồn này" })).toBeVisible();
    expect(screen.getByText("Có thể nguồn đã bị xoá.")).toBeVisible();
    expect(screen.getByRole("button", { name: "Thử lại" })).toBeVisible();
  });

  it("names what a destructive action destroys", () => {
    render(
      <DangerZone
        title="Xoá nhóm này"
        description="Thành viên và quyền bị gỡ."
        action={<Button tone="danger">Xoá nhóm</Button>}
      />,
    );
    const zone = screen.getByRole("region", { name: "Danger Zone" });
    expect(zone).toHaveTextContent("Xoá nhóm này");
    expect(zone).toHaveTextContent("Thành viên và quyền bị gỡ.");
    expect(screen.getByRole("button", { name: "Xoá nhóm" })).toBeVisible();
  });
});
