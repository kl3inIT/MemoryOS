import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { OwnerShell } from "./owner-shell";

const ACTOR_ID = "7b9f56d0-3026-4d2d-8e5f-1d6af6da93a1";

describe("OwnerShell", () => {
  it("renders the authenticated actor and session posture", () => {
    render(<OwnerShell actorId={ACTOR_ID} now={new Date("2026-08-19T08:00:00")} />);

    expect(screen.getByRole("heading", { name: "Good morning." })).toBeInTheDocument();
    expect(screen.getByLabelText(`Actor ID ${ACTOR_ID}`)).toBeInTheDocument();
    expect(screen.getByText("Private session")).toBeInTheDocument();
    expect(screen.getByText("Verified")).toBeInTheDocument();
  });
});
