import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ProviderCard } from "./provider-card";

describe("ProviderCard", () => {
  it("renders the requested landmark with its name, description and actions", () => {
    render(
      <ProviderCard
        as="section"
        aria-label="Exa"
        logo={<span>mark</span>}
        name="Exa"
        description="exa.ai"
        actions={<button type="button">Kết nối</button>}
      />,
    );
    const card = screen.getByRole("region", { name: "Exa" });
    expect(card).toHaveTextContent("exa.ai");
    expect(within(card).getByRole("button", { name: "Kết nối" })).toBeVisible();
    expect(card).not.toHaveAttribute("data-selected");
  });

  it("marks the provider in use", () => {
    render(<ProviderCard as="li" logo={null} name="SearXNG" selected />);
    expect(screen.getByRole("listitem")).toHaveAttribute("data-selected", "true");
  });
});
