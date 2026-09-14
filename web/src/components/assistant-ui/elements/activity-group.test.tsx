import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it } from "vitest";
import { Search } from "lucide-react";
import {
  ActivityGroupContent,
  ActivityGroupRoot,
  ActivityGroupTrigger,
  ActivityStep,
} from "./activity-group";

afterEach(cleanup);

function Group({ open, active }: { open?: boolean; active: boolean }) {
  return (
    <ActivityGroupRoot open={open}>
      <ActivityGroupTrigger
        label={active ? "Searching documents…" : "Searches: 1"}
        active={active}
        elapsed={active ? undefined : "2s"}
      />
      <ActivityGroupContent>
        <ActivityStep icon={<Search />} status="done" title="Searched documents" meta="2s">
          <span>annual leave policy</span>
        </ActivityStep>
        <ActivityStep icon={<Search />} status="failed" title="Read files · not completed" />
      </ActivityGroupContent>
    </ActivityGroupRoot>
  );
}

it("names the running step, then collapses to a summary that the user can reopen", async () => {
  const { rerender } = render(<Group open active />);
  expect(screen.getByRole("button", { name: /Searching documents…/ })).toHaveAttribute(
    "aria-expanded",
    "true",
  );
  expect(screen.getByText("annual leave policy")).toBeVisible();

  rerender(<Group active={false} />);
  const summary = screen.getByRole("button", { name: /Searches: 1/ });
  expect(summary).toHaveAttribute("aria-expanded", "false");
  expect(summary).toHaveTextContent("2s");
  expect(screen.queryByText("annual leave policy")).toBeNull();

  await userEvent.click(summary);
  expect(summary).toHaveAttribute("aria-expanded", "true");
  const steps = screen.getAllByRole("listitem");
  expect(steps.map((step) => step.getAttribute("data-status"))).toEqual(["done", "failed"]);
  expect(steps[1]).toHaveTextContent("Read files · not completed");
}, 20_000);
