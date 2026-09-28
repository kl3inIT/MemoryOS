import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { i18n } from "@/i18n/index";
import { getMeetingOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  handleAddMeetingMinutesItem,
  handleEditMeetingMinutesItem,
  handleGetMeeting,
  handleUpdateMeeting,
} from "@/lib/hey-api/msw.gen";
import type {
  MeetingDetail,
  MeetingMinutesItem,
  MeetingNewMinutesItemRequest,
} from "@/lib/hey-api/types.gen";
import { server } from "@/test/msw";
import { MeetingDetailsDialog } from "./meeting-details-dialog";
import { MinutesItems } from "./meeting-minutes";

const MEETING_ID = "0f6b3c1e-9a7d-4d5e-8c2b-6e1f4a9b3d77";

function item(id: string, text: string): MeetingMinutesItem {
  return {
    id,
    text,
    owner: null,
    due: null,
    quote: null,
    sourceUtteranceId: null,
    done: false,
    edited: false,
  };
}

function meeting(): MeetingDetail {
  return {
    id: MEETING_ID,
    title: "Giao ban tuần",
    kind: "IN_PERSON",
    language: "vi",
    participants: ["Anh Thanh"],
    terms: [],
    notes: "",
    status: "ENDED",
    provider: null,
    diarized: true,
    createdAt: "2026-09-24T09:00:00Z",
    endedAt: "2026-09-24T10:00:00Z",
    revision: 4,
    speakers: [],
    utterances: [],
    minutes: {
      status: "READY",
      failure: null,
      summary: "Cuộc họp chốt ngân sách.",
      kind: "Giao ban",
      generatedAt: "2026-09-24T10:01:00Z",
      decisions: [],
      actions: [item("a1", "Kiểm tra bảng cân đối")],
      edited: false,
      topics: [],
    },
    audio: { status: "NONE", failure: null, filename: null, sizeBytes: 0, provider: null },
    owned: true,
    readers: [],
    starred: [],
    bookmarks: [],
    correcting: false,
  };
}

/** The page's own read of the meeting, so a change folded into the cache shows as it would on the page. */
function Page({ children }: { children: (meeting: MeetingDetail) => React.ReactNode }) {
  const { data } = useQuery(getMeetingOptions({ path: { meetingId: MEETING_ID } }));
  return data ? children(data) : null;
}

function mount(children: (meeting: MeetingDetail) => React.ReactNode) {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={cache}>
      <Page>{children}</Page>
    </QueryClientProvider>,
  );
}

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  server.use(handleGetMeeting({ body: meeting() }));
});

describe("the minutes items", () => {
  it("writes in a task with its owner and due date, and shows it without reading the meeting again", async () => {
    const sent: MeetingNewMinutesItemRequest[] = [];
    server.use(
      handleAddMeetingMinutesItem(async ({ request }) => {
        const body = await request.json();
        sent.push(body);
        return HttpResponse.json({ ...item("a2", body.text), owner: body.owner, due: body.due });
      }),
    );
    const user = userEvent.setup();
    mount((current) => (
      <MinutesItems
        meeting={current}
        items={current.minutes.actions}
        kind="ACTION"
        onReveal={() => undefined}
      />
    ));

    await user.click(await screen.findByRole("button", { name: "Thêm việc" }));
    const add = screen.getByRole("button", { name: "Thêm" });
    expect(add).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "Nội dung" }), "Đặt phòng họp");
    await user.type(screen.getByLabelText("Người nhận"), "  Chị Hoa ");
    await user.click(add);

    expect(await screen.findByText("Đặt phòng họp")).toBeVisible();
    expect(screen.getByText("Chị Hoa")).toBeVisible();
    expect(screen.queryByRole("textbox", { name: "Nội dung" })).not.toBeInTheDocument();
    expect(sent).toEqual([{ kind: "ACTION", text: "Đặt phòng họp", owner: "Chị Hoa", due: null }]);
  });

  it("keeps an edit open with the failure when the server refuses it", async () => {
    server.use(
      handleEditMeetingMinutesItem(() =>
        HttpResponse.json({ status: 400, title: "Bad Request" }, { status: 400 }),
      ),
    );
    const user = userEvent.setup();
    mount((current) => (
      <MinutesItems
        meeting={current}
        items={current.minutes.actions}
        kind="ACTION"
        onReveal={() => undefined}
      />
    ));

    await user.click(await screen.findByRole("button", { name: "Sửa" }));
    await user.click(screen.getByRole("button", { name: "Lưu" }));

    expect(await screen.findByRole("alert")).toBeVisible();
    expect(screen.getByRole("textbox", { name: "Nội dung" })).toHaveValue("Kiểm tra bảng cân đối");
  });
});

describe("the meeting details", () => {
  it("renames the meeting and names who was in it, one name per comma", async () => {
    const sent: unknown[] = [];
    server.use(
      handleUpdateMeeting(async ({ request }) => {
        const body = await request.json();
        sent.push(body);
        return HttpResponse.json({ ...body, revision: 5 });
      }),
    );
    const user = userEvent.setup();
    mount((current) => (
      <>
        <h1>{current.title}</h1>
        <MeetingDetailsDialog meeting={current} />
      </>
    ));

    await user.click(await screen.findByRole("button", { name: "Sửa thông tin" }));
    const dialog = screen.getByRole("dialog", { name: "Thông tin cuộc họp" });
    const title = within(dialog).getByLabelText("Tên cuộc họp");
    await user.clear(title);
    await user.type(title, "Giao ban quý 4");
    await user.type(within(dialog).getByLabelText("Thành phần"), ", Chị Lan;Anh Minh");
    await user.click(within(dialog).getByRole("button", { name: "Lưu" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "Giao ban quý 4" })).toBeVisible();
    expect(sent).toEqual([
      { title: "Giao ban quý 4", participants: ["Anh Thanh", "Chị Lan", "Anh Minh"] },
    ]);
  });
});
