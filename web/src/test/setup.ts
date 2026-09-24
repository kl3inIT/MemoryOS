import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";
import { i18n } from "@/i18n";
// Registers the API client defaults and interceptors, as main.tsx does.
import "@/lib/api";

// Radix primitives measure their trigger; jsdom ships no ResizeObserver.
if (!("ResizeObserver" in globalThis)) {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

// jsdom implements neither pointer capture nor scrollIntoView, which Radix popup
// primitives (Select, DropdownMenu) call while opening.
beforeEach(() => {
  window.HTMLElement.prototype.hasPointerCapture ??= vi.fn(() => false);
  window.HTMLElement.prototype.setPointerCapture ??= vi.fn();
  window.HTMLElement.prototype.releasePointerCapture ??= vi.fn();
  window.HTMLElement.prototype.scrollIntoView ??= vi.fn();
});

beforeEach(async () => {
  await i18n.changeLanguage("en");
});

afterEach(() => {
  cleanup();
});
