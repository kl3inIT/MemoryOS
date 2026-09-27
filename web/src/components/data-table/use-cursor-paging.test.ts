import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useCursorPaging } from "@/components/data-table/use-cursor-paging";

describe("useCursorPaging", () => {
  it("walks forward by cursor and back through the cursors already seen", () => {
    const { result } = renderHook(() => useCursorPaging());

    act(() => result.current.goNext("b"));
    act(() => result.current.goNext("c"));
    expect(result.current).toMatchObject({ cursor: "c", page: 2, hasPrevious: true });

    act(() => result.current.goPrevious());
    expect(result.current).toMatchObject({ cursor: "b", page: 1 });

    act(() => result.current.goPrevious());
    expect(result.current).toMatchObject({ cursor: undefined, page: 0, hasPrevious: false });
  });

  it("returns to the first page once the current one lies past the last", () => {
    const { result, rerender } = renderHook(
      ({ totalPages }) => {
        const paging = useCursorPaging();
        paging.clamp(totalPages);
        return paging;
      },
      { initialProps: { totalPages: undefined as number | undefined } },
    );

    act(() => result.current.goNext("b"));
    rerender({ totalPages: 2 });
    expect(result.current.page).toBe(1);

    rerender({ totalPages: 1 });
    expect(result.current).toMatchObject({ cursor: undefined, page: 0 });
  });
});
