import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DocumentSetSourcePicker } from "./document-set-source-picker";

const options = [{ id: "source-1", name: "Kế toán 2024", type: "GOOGLE_DRIVE" }];

describe("DocumentSetSourcePicker", () => {
  it("names its search field so it is reachable without seeing the placeholder", () => {
    render(
      <DocumentSetSourcePicker
        options={options}
        known={[]}
        value={[]}
        disabled={false}
        invalid={false}
        onChange={() => {}}
      />,
    );

    expect(screen.getByRole("combobox", { name: "Search sources…" })).toBeVisible();
  });

  it("keeps the name while every Source is chosen and the placeholder changes", () => {
    render(
      <DocumentSetSourcePicker
        options={options}
        known={[]}
        value={["source-1"]}
        disabled={false}
        invalid={false}
        onChange={() => {}}
      />,
    );

    expect(screen.getByRole("combobox", { name: "Search sources…" })).toBeDisabled();
  });
});
