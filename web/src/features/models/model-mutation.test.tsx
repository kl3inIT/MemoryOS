import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { createMemoryOsQueryClient } from "@/lib/query-client";
import { isCatalogConflict } from "./model-catalog";
import { useModelCatalogBusy, useModelMutation } from "./model-mutation";

function harness() {
  const client = createMemoryOsQueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, wrapper };
}

function never(signal: AbortSignal, seen: AbortSignal[]) {
  seen.push(signal);
  return new Promise<void>(() => undefined);
}

describe("catalog mutations", () => {
  it("keeps one catalog action at a time across the page and frees the page as soon as one is cancelled", async () => {
    const { wrapper } = harness();
    const seen: AbortSignal[] = [];
    const { result } = renderHook(
      () => ({
        first: useModelMutation((signal) => never(signal, seen)),
        second: useModelMutation(async () => undefined),
        busy: useModelCatalogBusy(),
      }),
      { wrapper },
    );
    let first!: Promise<void>;
    act(() => {
      first = result.current.first.run();
    });
    await waitFor(() => expect(result.current.busy).toBe(true));
    // A second start of the same operation is refused rather than queued behind the first.
    await expect(result.current.first.run()).rejects.toThrow("already in progress");
    act(() => result.current.first.cancel());
    await expect(first).rejects.toThrow("discarded");
    expect(seen[0]?.aborted).toBe(true);
    await waitFor(() => expect(result.current.busy).toBe(false));
    expect(result.current.first).toMatchObject({ pending: false, error: null });
    expect(result.current.second.pending).toBe(false);
  });

  it("aborts the operation on unmount and collects it without applying its result", async () => {
    const { client, wrapper } = harness();
    const seen: AbortSignal[] = [];
    let resolve!: () => void;
    let applied = false;
    const { result, unmount } = renderHook(
      () =>
        useModelMutation(async (signal) => {
          seen.push(signal);
          await new Promise<void>((done) => {
            resolve = done;
          });
          signal.throwIfAborted();
          applied = true;
        }),
      { wrapper },
    );
    let run!: Promise<void>;
    act(() => {
      run = result.current.run();
    });
    await waitFor(() => expect(seen).toHaveLength(1));
    unmount();
    expect(seen[0]?.aborted).toBe(true);
    await expect(run).rejects.toThrow("discarded");
    resolve();
    await Promise.resolve();
    expect(applied).toBe(false);
    await waitFor(() => expect(client.getMutationCache().getAll()).toHaveLength(0));
  });

  it("settles with a sanitized error that still signals a conflict", async () => {
    const { client, wrapper } = harness();
    let conflicts = 0;
    const { result } = renderHook(
      () =>
        useModelMutation(
          async () => {
            throw new ApiError(409, { detail: "synthetic-provider-payload", request: "key" });
          },
          { onConflict: () => (conflicts += 1) },
        ),
      { wrapper },
    );
    await act(async () => {
      await result.current.run().catch(() => undefined);
    });
    expect(conflicts).toBe(1);
    await waitFor(() =>
      expect(result.current.error).toMatch(/^The catalog changed or this operation conflicts/),
    );
    const [mutation] = client.getMutationCache().getAll();
    expect(mutation!.state.variables).toBeInstanceOf(AbortSignal);
    expect(mutation!.state.error).toBeInstanceOf(ApiError);
    expect(isCatalogConflict(mutation!.state.error)).toBe(true);
    expect(mutation!.state.error!.cause).toBeUndefined();
    expect(JSON.stringify(mutation!.state.error)).not.toContain("synthetic-provider-payload");
  });

  it("leaves 401 to the global mutation handling, which purges private client state", async () => {
    const { client, wrapper } = harness();
    client.setQueryData(["private-catalog"], ["Private connection"]);
    client.setQueryData(getCurrentIdentityQueryKey(), { actorId: "synthetic" });
    const { result } = renderHook(
      () =>
        useModelMutation(async () => {
          throw new ApiError(401, { detail: "synthetic-provider-payload" });
        }),
      { wrapper },
    );
    await act(async () => {
      await result.current.run().catch(() => undefined);
    });
    expect(client.getQueryData(["private-catalog"])).toBeUndefined();
    expect(client.getMutationCache().getAll()).toHaveLength(0);
  });
});
