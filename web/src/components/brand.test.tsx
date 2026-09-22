import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Brand } from "./brand";

describe("Brand", () => {
  it("draws the MemoryOS wordmark and keeps the name for anyone who cannot see it", () => {
    const { container } = render(<Brand />);

    expect(screen.getByLabelText("MemoryOS")).toBeInTheDocument();
    expect(container.querySelector("[data-slot='brand-wordmark']")).toBeInTheDocument();
  });

  it("draws the app mark instead where the rail is too narrow for a wordmark", () => {
    const { container } = render(<Brand compact />);

    expect(container.querySelector("[data-slot='brand-wordmark']")).toBeNull();
    expect(container.querySelector("[data-slot='brand-mark']")).toBeInTheDocument();
  });
});
