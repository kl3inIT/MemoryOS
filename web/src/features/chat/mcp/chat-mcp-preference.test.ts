import { afterEach, expect, it, vi } from "vitest";
import { readMcpPreference, writeMcpPreference } from "./chat-mcp-preference";

const first = "0d7f6c2e-5f0b-4c7e-9a58-1f2f0f6f3a01";
const second = "0d7f6c2e-5f0b-4c7e-9a58-1f2f0f6f3a02";

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

it("remembers the chosen servers of one conversation, an empty choice included", () => {
  expect(readMcpPreference("tenant:alice", "chat-1")).toBeUndefined();
  writeMcpPreference("tenant:alice", "chat-1", [first, second]);
  expect(readMcpPreference("tenant:alice", "chat-1")).toEqual([first, second]);
  expect(readMcpPreference("tenant:bob", "chat-1")).toBeUndefined();
  expect(readMcpPreference("tenant:alice", "chat-2")).toBeUndefined();
  // Turning every server off is a choice: it is not replaced by the default.
  writeMcpPreference("tenant:alice", "chat-1", []);
  expect(readMcpPreference("tenant:alice", "chat-1")).toEqual([]);
});

it("writes nothing without a conversation or a choice", () => {
  writeMcpPreference("tenant:alice", undefined, [first]);
  writeMcpPreference("tenant:alice", "chat-1", undefined);
  writeMcpPreference(undefined, "chat-1", [first]);
  expect(localStorage.length).toBe(0);
});

it("ignores a stored value that is not a list of server ids and survives denied storage", () => {
  for (const stored of [
    "not json",
    '"text"',
    '["not-an-id"]',
    JSON.stringify(Array(9).fill(first)),
  ]) {
    localStorage.setItem("memoryos:mcp:alice:chat", stored);
    expect(readMcpPreference("alice", "chat")).toBeUndefined();
  }
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
    throw new Error("disabled");
  });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("full");
  });
  expect(readMcpPreference("alice", "chat")).toBeUndefined();
  expect(() => writeMcpPreference("alice", "chat", [first])).not.toThrow();
});
