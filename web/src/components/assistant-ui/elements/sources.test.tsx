import { fireEvent, render, screen } from "@testing-library/react";
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

it("does not request favicons for internal document sources and retains expanded state", () => {
  const { container } = render(
    <Sources count={2} sources={[{}, {}]} aria-expanded aria-controls="panel" />,
  );
  expect(container.querySelectorAll("img")).toHaveLength(0);
  expect(screen.getByRole("button", { name: "Nguồn 2" })).toHaveAttribute("aria-expanded", "true");
  expect(screen.getByRole("button")).toHaveAttribute("aria-controls", "panel");
});

it("reuses favicon fallback and retries when the domain changes", () => {
  const { container, rerender } = render(<SourceIcon domain="example.com" />);
  const image = container.querySelector("img")!;
  expect(image).toHaveAttribute("src", "https://icons.duckduckgo.com/ip3/example.com.ico");
  expect(image).toHaveAttribute("referrerpolicy", "no-referrer");
  expect(image).toHaveAttribute("crossorigin", "anonymous");
  fireEvent.error(image);
  expect(container.querySelector('[data-slot="source-icon-fallback"]')).toHaveTextContent("E");
  rerender(<SourceIcon domain="react.dev" />);
  expect(container.querySelector("img")).toHaveAttribute(
    "src",
    "https://icons.duckduckgo.com/ip3/react.dev.ico",
  );
});

it("renders the letter fallback without a favicon request when favicons are not allowed", () => {
  const { container } = render(<SourceIcon domain="intranet.example" favicon={false} />);
  expect(container.querySelector("img")).toBeNull();
  expect(container.querySelector('[data-slot="source-icon-fallback"]')).toHaveTextContent("I");
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

it("uses a globe or no fallback where a letter would read as part of a citation number", () => {
  const { container, rerender } = render(
    <SourceIcon domain="react.dev" favicon={false} fallback="globe" />,
  );
  expect(container.querySelector('svg[data-slot="source-icon-fallback"]')).not.toBeNull();
  expect(container).not.toHaveTextContent("R");
  rerender(<SourceIcon domain="react.dev" favicon={false} fallback="none" />);
  expect(container.querySelector('[data-slot="source-icon-fallback"]')).toBeNull();
});
