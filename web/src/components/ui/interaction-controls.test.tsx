import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ArrowRight } from "lucide-react";
import { describe, expect, it, vi } from "vitest";

import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { TextButton } from "@/components/ui/text-button";

describe("interaction controls", () => {
  it("keeps ordinary actions out of form submission by default", () => {
    render(<Button>Save</Button>);

    expect(screen.getByRole("button", { name: "Save" })).toHaveAttribute("type", "button");
  });

  it("preserves explicit submit and composed link semantics", () => {
    const { rerender } = render(<Button type="submit">Submit</Button>);
    expect(screen.getByRole("button", { name: "Submit" })).toHaveAttribute("type", "submit");

    rerender(
      <Button asChild prominence="secondary">
        <a href="/admin">Administration</a>
      </Button>,
    );
    const link = screen.getByRole("link", { name: "Administration" });
    expect(link).toHaveAttribute("href", "/admin");
    expect(link).not.toHaveAttribute("type");
    expect(link).toHaveAttribute("data-prominence", "secondary");
  });

  it("blocks repeated activation while an action is pending", async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    render(
      <Button pending onClick={onClick}>
        Saving
      </Button>,
    );

    const button = screen.getByRole("button", { name: "Saving" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toHaveAttribute("data-pending", "true");
    await user.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("blocks mouse and keyboard activation for disabled or pending composed actions", () => {
    const activation = vi.fn();
    const childProps = {
      href: "/admin",
      onClick: activation,
      onClickCapture: activation,
      onKeyDown: activation,
      onKeyDownCapture: activation,
    };
    render(
      <>
        <Button asChild disabled>
          <a {...childProps}>Disabled button link</a>
        </Button>
        <Button asChild pending>
          <a {...childProps}>Pending button link</a>
        </Button>
        <TextButton asChild disabled>
          <a {...childProps}>Disabled text link</a>
        </TextButton>
        <TextButton asChild pending>
          <a {...childProps}>Pending text link</a>
        </TextButton>
        <IconButton asChild disabled aria-label="Disabled icon link">
          <a {...childProps}>
            <ArrowRight />
          </a>
        </IconButton>
        <IconButton asChild pending aria-label="Pending icon link">
          <a {...childProps}>
            <ArrowRight />
          </a>
        </IconButton>
      </>,
    );

    for (const name of [
      "Disabled button link",
      "Pending button link",
      "Disabled text link",
      "Pending text link",
      "Disabled icon link",
      "Pending icon link",
    ]) {
      const link = screen.getByRole("link", { name });
      expect(link).toHaveAttribute("aria-disabled", "true");
      expect(link).toHaveAttribute("tabindex", "-1");
      fireEvent.keyDown(link, { key: "Enter" });
      fireEvent.keyDown(link, { key: " " });
      fireEvent.click(link);
    }

    expect(activation).not.toHaveBeenCalled();
  });

  it("gives quiet and icon-only actions distinct accessible contracts", () => {
    render(
      <>
        <TextButton tone="danger">Remove</TextButton>
        <IconButton aria-label="Open details" prominence="internal">
          <ArrowRight />
        </IconButton>
      </>,
    );

    const textButton = screen.getByRole("button", { name: "Remove" });
    expect(textButton).toHaveAttribute("data-slot", "text-button");
    expect(textButton).toHaveClass("border-0", "bg-transparent", "p-0");

    const iconButton = screen.getByRole("button", { name: "Open details" });
    expect(iconButton).toHaveAttribute("data-slot", "icon-button");
    expect(iconButton).toHaveAttribute("data-prominence", "internal");
  });

  it.each(["sm", "md", "lg"] as const)(
    "shares the %s size contract across container and native controls",
    (size) => {
      render(
        <>
          <Button size={size}>Apply</Button>
          <IconButton aria-label="Refresh" size={size}>
            <ArrowRight />
          </IconButton>
          <Input aria-label="Email" size={size} />
          <Select aria-label="Status" size={size} defaultValue="open">
            <option value="open">Open</option>
          </Select>
        </>,
      );

      for (const control of [
        screen.getByRole("button", { name: "Apply" }),
        screen.getByRole("button", { name: "Refresh" }),
        screen.getByRole("textbox", { name: "Email" }),
        screen.getByRole("combobox", { name: "Status" }),
      ]) {
        expect(control).toHaveAttribute("data-size", size);
      }
    },
  );
});
