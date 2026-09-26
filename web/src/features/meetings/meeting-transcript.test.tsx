import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import type { MeetingRecorder, RecorderSnapshot } from "./meeting-recorder";
import { Transcript } from "./meeting-transcript";
import type { MeetingDetail } from "./meetings-api";

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

function show(detail: MeetingDetail, recorder?: MeetingRecorder) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Transcript meeting={detail} recorder={recorder} onStar={vi.fn()} />
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

it("shows only the starred lines when asked, and counts search hits across the transcript", async () => {
  const user = userEvent.setup();
  const list = show(meeting(300, ["line-107", "line-250"]));

  await user.type(screen.getByRole("textbox", { name: "Tìm trong transcript" }), "doanh thu");
  expect(screen.getByText("1/3")).toBeInTheDocument();

  await user.click(screen.getByRole("button", { name: "Câu đã đánh dấu (2)" }));
  const lines = within(list).getAllByRole("listitem");
  expect(lines.map((line) => line.id)).toEqual(["line-107", "line-250"]);
  // Only the starred line holding a hit is counted now.
  expect(screen.getByText("1/1")).toBeInTheDocument();
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
