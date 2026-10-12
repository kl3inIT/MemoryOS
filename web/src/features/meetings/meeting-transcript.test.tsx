import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import type { MeetingRecorder, RecorderSnapshot } from "./meeting-recorder";
import { Transcript } from "./meeting-transcript";
import type { MeetingCorrection, MeetingDetail } from "./meetings-api";

const LAID_OUT = { offsetHeight: 600, offsetWidth: 800 };

// Only the lines inside the scrolling window are rendered, and jsdom lays nothing out, so the window would be
// zero high and the transcript would come out empty. These are the two measurements the window is taken from.
beforeEach(async () => {
  await i18n.changeLanguage("vi");
  for (const [property, value] of Object.entries(LAID_OUT))
    Object.defineProperty(HTMLElement.prototype, property, { configurable: true, value });
});

afterEach(() => {
  for (const property of Object.keys(LAID_OUT))
    Reflect.deleteProperty(HTMLElement.prototype, property);
  cleanup();
});

function meeting(lines: number, starred: string[] = []): MeetingDetail {
  return {
    id: "meeting-1",
    title: "Giao ban",
    kind: "IN_PERSON",
    owned: false,
    status: "ENDED",
    participants: [],
    speakers: [{ track: "MIC", label: "1", name: null }],
    starred,
    minutes: { topics: [] },
    utterances: Array.from({ length: lines }, (_, index) => ({
      id: `line-${index}`,
      track: "MIC",
      speaker: "1",
      startMs: index * 5_000,
      endMs: index * 5_000 + 4_000,
      text: index % 100 === 7 ? `Doanh thu quý ${index}` : `Câu số ${index}`,
      confidence: 1,
      spans: [],
    })),
  } as unknown as MeetingDetail;
}

function show(
  detail: MeetingDetail,
  recorder?: MeetingRecorder,
  corrections: MeetingCorrection[] = [],
) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Transcript
        meeting={detail}
        recorder={recorder}
        timeline={[]}
        corrections={corrections}
        toolsSlot={document.body}
        onReading={vi.fn()}
        onStar={vi.fn()}
      />
    </QueryClientProvider>,
  );
  return screen.getByRole("region", { name: "Transcript" });
}

it("renders only the lines in view of a long transcript, each saying where it sits", () => {
  const list = show(meeting(2_000));

  const lines = within(list).getAllByRole("listitem");
  expect(lines.length).toBeGreaterThan(0);
  expect(lines.length).toBeLessThan(50);
  expect(lines[0]).toHaveTextContent("Câu số 0");
  expect(lines[0]).toHaveAttribute("aria-setsize", "2000");
  expect(lines[0]).toHaveAttribute("aria-posinset", "1");
});
it("follows server transcript updates while a shared meeting is recording", () => {
  // jsdom lays nothing out, so the growth the browser would report is reported by hand.
  const grown = new Map<Element, () => void>();
  vi.stubGlobal(
    "ResizeObserver",
    class {
      private readonly report: () => void;
      constructor(report: () => void) {
        this.report = report;
      }
      observe(target: Element) {
        grown.set(target, this.report);
      }
      unobserve() {}
      disconnect() {}
    },
  );
  const active = { ...meeting(1), status: "RECORDING" as const };
  const first = active.utterances[0];
  if (!first) throw new Error("Live transcript fixture needs an utterance.");
  const client = new QueryClient();
  const view = render(
    <QueryClientProvider client={client}>
      <Transcript
        meeting={active}
        recorder={undefined}
        timeline={[]}
        corrections={[]}
        toolsSlot={document.body}
        onReading={vi.fn()}
        onStar={vi.fn()}
      />
    </QueryClientProvider>,
  );
  const lines = screen.getByRole("region", { name: "Transcript" }).firstElementChild;
  if (!(lines instanceof HTMLElement)) throw new Error("The transcript renders its lines.");
  // The lines end below the window of the page, whose own box jsdom leaves at zero.
  lines.getBoundingClientRect = () => ({ bottom: 1_200 }) as DOMRect;
  const shown = vi.fn();
  lines.scrollIntoView = shown;
  // Opening the page reports its size once, and moves nothing.
  grown.get(lines)?.();
  expect(shown).not.toHaveBeenCalled();

  view.rerender(
    <QueryClientProvider client={client}>
      <Transcript
        meeting={{
          ...active,
          utterances: [
            ...active.utterances,
            {
              ...first,
              id: "line-1",
              startMs: 5_000,
              endMs: 9_000,
              text: "Câu số 1",
            },
          ],
        }}
        recorder={undefined}
        timeline={[]}
        corrections={[]}
        toolsSlot={document.body}
        onReading={vi.fn()}
        onStar={vi.fn()}
      />
    </QueryClientProvider>,
  );
  grown.get(lines)?.();

  expect(shown).toHaveBeenCalledWith({ block: "end" });
  client.clear();
  vi.unstubAllGlobals();
});

it("shows only the starred lines when asked, and counts search hits across the transcript", async () => {
  const user = userEvent.setup();
  const list = show(meeting(300, ["line-107", "line-250"]));

  await user.type(screen.getByRole("textbox", { name: "Tìm trong transcript" }), "doanh thu");
  expect(screen.getByText("1/3")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Câu đã gắn sao (2)" }));
  const lines = within(list).getAllByRole("listitem");
  expect(lines.map((line) => line.id)).toEqual(["line-107", "line-250"]);
  // Only the starred line holding a hit is counted now.
  expect(screen.getByText("1/1")).toBeInTheDocument();
});

it("opens the search from the browser's find shortcut and walks the hits with Enter", async () => {
  const user = userEvent.setup();
  show(meeting(300));
  const find = screen.getByRole("textbox", { name: "Tìm trong transcript" });

  // Only the lines in view are rendered, so the browser's own find would miss most of the meeting.
  await user.keyboard("{Control>}f{/Control}");
  expect(find).toHaveFocus();

  await user.keyboard("doanh thu");
  expect(screen.getByText("1/3")).toBeInTheDocument();
  await user.keyboard("{Enter}");
  expect(screen.getByText("2/3")).toBeInTheDocument();
  await user.keyboard("{Shift>}{Enter}{/Shift}");
  expect(screen.getByText("1/3")).toBeInTheDocument();

  await user.keyboard("{Escape}");
  expect(find).toHaveValue("");
});

it("ends a transcript being recorded with the sentences still being said", () => {
  const snapshot: RecorderSnapshot = {
    phase: "recording",
    elapsedMs: 12_000,
    tracks: [],
    previews: { MIC: { speaker: "1", text: "Chúng ta bắt đầu" } },
  };
  const recorder = {
    subscribe: () => () => undefined,
    getSnapshot: () => snapshot,
  } as unknown as MeetingRecorder;

  const list = show(meeting(0), recorder);

  expect(within(list).getByText("Chúng ta bắt đầu…")).toBeInTheDocument();
  expect(within(list).getByText("đang nói")).toBeInTheDocument();
});

it("marks a change in the line it changed, with what was heard there and the way back", async () => {
  const change = {
    id: "correction-1",
    utteranceId: "line-1",
    start: 0,
    before: "Cau",
    after: "Câu",
    status: "ACCEPTED",
  } as MeetingCorrection;
  // A change whose line no longer reads as it left it is not marked anywhere.
  const stale = { ...change, id: "correction-2", utteranceId: "line-2", after: "Đoạn" };

  const list = show(meeting(3), undefined, [change, stale]);

  expect(within(list).getAllByRole("button", { name: /^Đã sửa/ })).toHaveLength(1);
  const line = within(list).getAllByRole("listitem")[1];
  if (!line) throw new Error("the changed line is not rendered");
  await userEvent.click(within(line).getByRole("button", { name: "Đã sửa “Cau” thành “Câu”" }));
  const heard = screen.getByRole("dialog");
  expect(within(heard).getByText("Cau")).toBeVisible();
  expect(within(heard).getByRole("button", { name: "Hoàn tác" })).toBeEnabled();
});
