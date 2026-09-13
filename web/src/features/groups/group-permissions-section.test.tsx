import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import type { GroupCapability, GroupSummary } from "@/lib/hey-api/types.gen";
import { GroupPermissionsSection } from "./group-permissions-section";

const registry: GroupCapability[] = [
  {
    id: "SYSTEM_ADMIN",
    label: "Administrator access",
    description: "Includes every product capability.",
    editable: false,
    implies: [
      "SYSTEM_BASIC",
      "SEARCH_READ",
      "CHAT_READ",
      "CHAT_WRITE",
      "IMAGE_GENERATE",
      "LLM_GATEWAY_USE",
      "USERS_MANAGE",
      "GROUPS_READ",
      "GROUPS_MANAGE",
      "SOURCES_READ",
      "SOURCES_MANAGE",
      "SOURCES_DELETE",
      "MODELS_MANAGE",
    ],
  },
  {
    id: "SYSTEM_BASIC",
    label: "Basic access",
    description:
      "Includes Search and reserved upcoming Chat, image generation, and LLM gateway permissions.",
    editable: false,
    implies: ["SEARCH_READ", "CHAT_READ", "CHAT_WRITE", "IMAGE_GENERATE", "LLM_GATEWAY_USE"],
  },
  {
    id: "SEARCH_READ",
    label: "Search documents",
    description: "Read eligible search results.",
    editable: false,
    implies: [],
  },
  {
    id: "CHAT_READ",
    label: "Read chats",
    description: "Reserved for upcoming chat reading; not a supported feature.",
    editable: false,
    implies: [],
  },
  {
    id: "CHAT_WRITE",
    label: "Write chats",
    description: "Reserved for upcoming chat writing; not a supported feature.",
    editable: false,
    implies: ["CHAT_READ"],
  },
  {
    id: "IMAGE_GENERATE",
    label: "Generate images",
    description: "Reserved for upcoming image generation; not a supported feature.",
    editable: false,
    implies: [],
  },
  {
    id: "LLM_GATEWAY_USE",
    label: "Use LLM gateway",
    description: "Reserved for upcoming LLM gateway use; not a supported feature.",
    editable: false,
    implies: [],
  },
  {
    id: "USERS_MANAGE",
    label: "Manage users",
    description: "Administer tenant users.",
    editable: true,
    implies: [],
  },
  {
    id: "GROUPS_READ",
    label: "View groups",
    description: "Derived from Manage groups; cannot be granted directly.",
    editable: false,
    implies: [],
  },
  {
    id: "GROUPS_MANAGE",
    label: "Manage groups",
    description: "Administer tenant groups.",
    editable: true,
    implies: ["GROUPS_READ"],
  },
  {
    id: "SOURCES_READ",
    label: "View Sources",
    description: "Derived from Manage Sources; cannot be granted directly.",
    editable: false,
    implies: [],
  },
  {
    id: "SOURCES_MANAGE",
    label: "Manage Sources",
    description: "View, create, configure, upload, reindex and delete Sources across the tenant.",
    editable: true,
    implies: ["SOURCES_READ", "SOURCES_DELETE"],
  },
  {
    id: "SOURCES_DELETE",
    label: "Delete Sources",
    description: "Derived from Manage Sources; cannot be granted directly.",
    editable: false,
    implies: ["SOURCES_READ"],
  },
  {
    id: "MODELS_MANAGE",
    label: "Manage models",
    description: "Manage model providers and the tenant model catalog.",
    editable: true,
    implies: [],
  },
];

function renderPermissions({
  selected,
  systemKey = null,
  editable = true,
  capabilities = registry,
  loading = false,
  error = false,
}: {
  selected: GroupCapability["id"][];
  systemKey?: GroupSummary["systemKey"];
  editable?: boolean;
  capabilities?: GroupCapability[];
  loading?: boolean;
  error?: boolean;
}) {
  const onChange = vi.fn();
  const onRetry = vi.fn();
  function Permissions() {
    const [grants, setGrants] = useState(new Set(selected));
    return (
      <GroupPermissionsSection
        registry={capabilities}
        selected={grants}
        systemKey={systemKey}
        editable={editable}
        loading={loading}
        error={error}
        onRetry={onRetry}
        onChange={(next) => {
          setGrants(next);
          onChange(next);
        }}
      />
    );
  }
  render(<Permissions />);
  return { onChange, onRetry };
}

describe("GroupPermissionsSection", () => {
  it.each([
    ["ADMIN", "SYSTEM_ADMIN"],
    ["BASIC", "SYSTEM_BASIC"],
  ] as const)("shows only its own readonly bundle for %s", async (systemKey, grant) => {
    const user = userEvent.setup();
    const { onChange } = renderPermissions({ selected: [grant], systemKey });
    const ownGrant = registry.find((capability) => capability.id === grant)!;
    expect(screen.getAllByRole("switch")).toHaveLength(1);
    const control = screen.getByRole("switch", { name: ownGrant.label });
    expect(control).toBeDisabled();
    expect(control).toBeChecked();
    await user.click(control);
    for (const capability of registry.filter((entry) => entry.id !== grant)) {
      expect(screen.queryByRole("switch", { name: capability.label })).not.toBeInTheDocument();
      expect(screen.queryByText(capability.description)).not.toBeInTheDocument();
    }
    expect(onChange).not.toHaveBeenCalled();
  });

  it("does not invent ON grants from the system group identity", () => {
    renderPermissions({ selected: [], systemKey: "ADMIN" });
    for (const control of screen.getAllByRole("switch")) {
      expect(control).toHaveAttribute("aria-checked", "false");
    }
  });

  it("lets ordinary groups edit direct grants without auto-selecting implied permissions", async () => {
    const user = userEvent.setup();
    const { onChange } = renderPermissions({ selected: [] });
    expect(screen.getAllByRole("switch")).toHaveLength(4);
    for (const capability of registry.filter((entry) => !entry.editable)) {
      expect(screen.queryByRole("switch", { name: capability.label })).not.toBeInTheDocument();
    }
    const manage = screen.getByRole("switch", { name: "Manage Sources" });
    expect(manage).not.toBeChecked();
    await user.click(manage);
    expect(onChange).toHaveBeenLastCalledWith(new Set(["SOURCES_MANAGE"]));
    await user.click(screen.getByRole("switch", { name: "Manage groups" }));
    expect(onChange).toHaveBeenLastCalledWith(new Set(["SOURCES_MANAGE", "GROUPS_MANAGE"]));
    manage.focus();
    await user.keyboard(" ");
    expect(onChange).toHaveBeenLastCalledWith(new Set(["GROUPS_MANAGE"]));
    await user.click(screen.getByRole("switch", { name: "Manage models" }));
    expect(onChange).toHaveBeenLastCalledWith(new Set(["GROUPS_MANAGE", "MODELS_MANAGE"]));
  });

  it("keeps grants disabled for an ordinary readonly viewer", async () => {
    const user = userEvent.setup();
    const { onChange } = renderPermissions({ selected: ["SOURCES_MANAGE"], editable: false });
    expect(screen.getByRole("switch", { name: "Manage Sources" })).toBeChecked();
    for (const control of screen.getAllByRole("switch")) {
      expect(control).toBeDisabled();
      await user.click(control);
    }
    expect(onChange).not.toHaveBeenCalled();
  });

  it("collapses the permission card without changing selected grants", async () => {
    const user = userEvent.setup();
    const { onChange } = renderPermissions({ selected: ["SYSTEM_BASIC"], systemKey: "BASIC" });
    const toggle = screen.getByRole("button", { name: "Toggle group permissions" });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    await user.click(toggle);
    expect(screen.getByRole("switch", { name: "Basic access" })).toBeChecked();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("does not expose editable controls when the registry fails and allows retry", async () => {
    const user = userEvent.setup();
    const { onChange, onRetry } = renderPermissions({ selected: ["SOURCES_MANAGE"], error: true });
    expect(screen.getByRole("alert")).toBeVisible();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(onRetry).toHaveBeenCalledOnce();
    expect(onChange).not.toHaveBeenCalled();
  });
});
