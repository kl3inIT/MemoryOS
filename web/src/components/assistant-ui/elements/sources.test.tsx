import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n";
import { Sources } from "./sources";
import { SourceIcon } from "./source-icon";

beforeEach(async () => {
  await i18n.changeLanguage("vi");
});

it("shows one Sources control with up to three distinct icon types, not a row of titles", async () => {
  const click = vi.fn();
  const { container } = render(
    <Sources
      count={6}
      onClick={click}
      sources={[
        {},
        {},
        { url: "https://example.com/article" },
        { url: "https://example.com/other" },
        { url: "https://react.dev/learn" },
        { url: "https://spring.io/docs" },
      ]}
    />,
  );
  const button = screen.getByRole("button", { name: "Nguồn 6" });
  expect(button).toHaveTextContent(/^Nguồn$/);
  expect(container.querySelector('[data-slot="source-icon-stack"]')?.children).toHaveLength(3);
  expect(container.querySelectorAll('[data-slot="source-document-icon"]')).toHaveLength(1);
  expect(screen.queryAllByRole("link")).toHaveLength(0);
  await userEvent.click(button);
  expect(click).toHaveBeenCalledTimes(1);
});

it("shows document icons for internal sources and retains expanded state", () => {
  const { container } = render(
    <Sources count={2} sources={[{}, {}]} aria-expanded aria-controls="panel" />,
  );
  expect(container.querySelectorAll("img")).toHaveLength(0);
  expect(screen.getByRole("button", { name: "Nguồn 2" })).toHaveAttribute("aria-expanded", "true");
  expect(screen.getByRole("button")).toHaveAttribute("aria-controls", "panel");
});

it("shows a bundled brand mark for a well-known site, without any request", () => {
  const { container, rerender } = render(<SourceIcon domain="docs.github.com" />);
  expect(container.querySelector('svg[data-slot="source-icon"]')).not.toBeNull();
  expect(container.querySelector("img")).toBeNull();
  // A subdomain with its own mark takes it over its parent's.
  rerender(<SourceIcon domain="aws.amazon.com" />);
  const aws = container.querySelector('svg[data-slot="source-icon"]')!.innerHTML;
  rerender(<SourceIcon domain="www.amazon.com" />);
  expect(container.querySelector('svg[data-slot="source-icon"]')!.innerHTML).not.toBe(aws);
});

it("shows the globe for any other site and for a name that is not a web host", () => {
  const { container, rerender } = render(<SourceIcon domain="spring.io" />);
  expect(container.querySelector('svg[data-slot="source-icon-fallback"]')).not.toBeNull();
  expect(container.querySelector('[data-slot="source-icon"]')).toBeNull();
  // A look-alike host does not borrow a brand.
  rerender(<SourceIcon domain="notgithub.com" />);
  expect(container.querySelector('[data-slot="source-icon"]')).toBeNull();
  rerender(<SourceIcon domain="github.com" brand={false} />);
  expect(container.querySelector('[data-slot="source-icon"]')).toBeNull();
  expect(container.querySelector('svg[data-slot="source-icon-fallback"]')).not.toBeNull();
});

it("localizes the generic label without a visible count", async () => {
  await i18n.changeLanguage("en");
  render(<Sources count={1} sources={[{}]} />);
  expect(screen.getByRole("button")).toHaveTextContent(/^Sources$/);
});

it("stacks one icon per document kind because chip-size icons carry no provider badge", () => {
  const { container } = render(
    <Sources
      count={3}
      sources={[
        { mediaType: "application/pdf", sourceTypes: ["FILE"] },
        { mediaType: "application/pdf", sourceTypes: ["GOOGLE_DRIVE"] },
        { mediaType: "application/vnd.google-apps.spreadsheet", sourceTypes: ["GOOGLE_DRIVE"] },
      ]}
    />,
  );
  const kinds = [...container.querySelectorAll('[data-slot="document-source-icon"]')].map((icon) =>
    icon.getAttribute("data-kind"),
  );
  expect(kinds).toEqual(["pdf", "spreadsheet"]);
});

it("shows no fallback where it would read as part of a citation number", () => {
  const { container } = render(<SourceIcon domain="spring.io" fallback="none" />);
  expect(container).toBeEmptyDOMElement();
});
