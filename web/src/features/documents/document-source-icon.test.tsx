import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DocumentSourceIcon } from "./document-source-icon";
import { documentSourceLabels } from "./document-source-presentation";

describe("documentSourceLabels", () => {
  it("returns untranslated type and distinct provider labels", () => {
    expect(
      documentSourceLabels("application/vnd.google-apps.spreadsheet", [
        "GOOGLE_DRIVE",
        "FILE",
        "GOOGLE_DRIVE",
      ]),
    ).toEqual({
      type: "Google Sheets",
      providers: ["Google Drive", "Tệp tải lên"],
    });
    expect(documentSourceLabels(null, undefined)).toEqual({ type: undefined, providers: [] });
  });
});

describe("DocumentSourceIcon", () => {
  it("badges external providers only and stays decorative", () => {
    const { container, rerender } = render(
      <DocumentSourceIcon mediaType="application/pdf" sourceTypes={["FILE", "GOOGLE_DRIVE"]} />,
    );
    const icon = container.querySelector('[data-slot="document-source-icon"]')!;
    expect(icon.getAttribute("aria-hidden")).toBe("true");
    expect(icon.getAttribute("data-kind")).toBe("pdf");
    expect(icon.getAttribute("data-provider")).toBe("google_drive");
    expect(container.querySelector('[data-slot="document-source-provider"]')).not.toBeNull();

    rerender(<DocumentSourceIcon sourceTypes={["FILE"]} />);
    expect(
      container.querySelector('[data-slot="document-source-icon"]')!.getAttribute("data-kind"),
    ).toBe("generic");
    expect(container.querySelector('[data-slot="document-source-provider"]')).toBeNull();
  });
});
