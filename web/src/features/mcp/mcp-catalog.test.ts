import { expect, it } from "vitest";
import type { McpServerView } from "@/lib/hey-api/types.gen";
import { availableCatalog, catalogEntryOf, mcpCatalog, presetOf } from "./mcp-catalog";

const added = (url: string) => ({ url }) as McpServerView;

it("offers only the entries whose host is not added yet", () => {
  const left = availableCatalog([
    added("https://mcp.linear.app/mcp"),
    added("https://drivemcp.googleapis.com/mcp/v1"),
  ]);
  expect(left.map((entry) => entry.id)).not.toContain("linear");
  expect(left.map((entry) => entry.id)).not.toContain("drive");
  expect(left).toHaveLength(mcpCatalog.length - 2);
});

it("recognises a server added from the catalog by its host", () => {
  expect(catalogEntryOf("https://gmailmcp.googleapis.com/mcp/v1")?.setupUrl).toBe(
    "https://console.cloud.google.com/apis/credentials",
  );
  expect(catalogEntryOf("https://internal.example/mcp")).toBeUndefined();
});

it("asks Google for an offline grant so a connection outlives its first token", () => {
  for (const id of ["drive", "gmail"]) {
    const entry = mcpCatalog.find((candidate) => candidate.id === id)!;
    expect(presetOf(entry).oauthAdditionalParameters).toEqual({
      access_type: "offline",
      prompt: "consent",
    });
  }
});

it("uses each entry's id as a valid, unique short code", () => {
  const ids = mcpCatalog.map((entry) => entry.id);
  expect(new Set(ids).size).toBe(ids.length);
  for (const id of ids) expect(id).toMatch(/^[a-z0-9]{1,16}$/);
});
