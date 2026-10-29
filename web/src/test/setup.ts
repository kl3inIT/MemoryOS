import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach, vi } from "vitest";
import { server } from "./msw";

beforeAll(() => server.listen({ onUnhandledFrame: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const browser = typeof window !== "undefined";

// Radix primitives measure their trigger; jsdom ships no ResizeObserver.
if (browser && !("ResizeObserver" in globalThis)) {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}

// jsdom ships no matchMedia; tests run as a wide screen that asks for nothing, and may stub their own.
if (browser && typeof window.matchMedia !== "function") {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener() {},
      removeEventListener() {},
      addListener() {},
      removeListener() {},
      dispatchEvent: () => false,
    }) as MediaQueryList;
}

// jsdom implements neither pointer capture nor scrollIntoView, which Radix popup
// primitives (Select, DropdownMenu) call while opening.
beforeEach(() => {
  if (!browser) return;
  window.HTMLElement.prototype.hasPointerCapture ??= vi.fn(() => false);
  window.HTMLElement.prototype.setPointerCapture ??= vi.fn();
  window.HTMLElement.prototype.releasePointerCapture ??= vi.fn();
  window.HTMLElement.prototype.scrollIntoView ??= vi.fn();
});

// The API client takes its base address from the page and the catalog sets the document's language when they load,
// so only a browser environment loads them. Loading the client registers its defaults and interceptors, as main.tsx does.
if (browser) {
  await import("@/lib/api");
  const { i18n } = await import("@/i18n");
  beforeEach(async () => {
    await i18n.changeLanguage("en");
  });
}

afterEach(() => {
  cleanup();
});
