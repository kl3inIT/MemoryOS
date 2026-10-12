import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { useAppTranslation } from "@/i18n/use-app-translation";
import {
  bookmarkMeetingMomentMutation,
  deleteMeetingMutation,
  getMeetingOptions,
  starMeetingUtteranceMutation,
  unstarMeetingUtteranceMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { openMeetingSources, pickMeetingTab, ShareCancelledError } from "./meeting-capture";
import type { MeetingPane } from "./meeting-panes";
import type { MeetingRecorder } from "./meeting-recorder";
import {
  endMeeting,
  startRecording,
  useActiveMeeting,
  useRecordingFailure,
} from "./meeting-session";
import type { TranscriptTarget } from "./meeting-transcript";
import {
  invalidateMeetingList,
  formatClock,
  meetingQueryKey,
  patchMeeting,
  trackOffsets,
  type MeetingDetail,
} from "./meetings-api";
import { useRecorderValue } from "./recorder-state";
import { useFailureText } from "./use-failure-text";

/** A meeting still being worked on without a socket: a recording transcribed, minutes written or a correction pass. */
function waiting(meeting: MeetingDetail | undefined) {
  return (
    !!meeting &&
    (meeting.status === "TRANSCRIBING" ||
      meeting.minutes.status === "PENDING" ||
      meeting.minutes.status === "RUNNING" ||
      meeting.correcting)
  );
}

/**
 * What the recorder of this meeting shows, if it is being recorded in this tab. The page follows only what changes
 * its layout; the clock, the meters and the live sentences subscribe where they are shown, so a level reading does
 * not re-render the transcript and the minutes.
 */
function useLiveRecording(meetingId: string) {
  const live = useActiveMeeting();
  const recorder = live?.meetingId === meetingId ? live.recorder : undefined;
  const phase = useRecorderValue(recorder, (snapshot) => snapshot.phase);
  const hasTab = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.track === "TAB"),
  );
  const tabEnded = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.track === "TAB" && track.ended),
  );
  const tabQuiet = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.track === "TAB" && track.quiet && !track.ended),
  );
  const reconnecting = useRecorderValue(recorder, (snapshot) =>
    snapshot.tracks.some((track) => track.reconnecting),
  );
  const failure = useRecordingFailure();
  return {
    /** Another meeting, or this one, is recording in this tab. */
    busy: !!live,
    recorder,
    recording: !!recorder && (phase === "recording" || phase === "paused" || phase === "stopping"),
    hasTab,
    tabEnded,
    tabQuiet,
    reconnecting,
    /** Why the last recording of this meeting stopped on its own. */
    stoppedBy: failure?.meetingId === meetingId ? failure.code : undefined,
  };
}

/** The recorded moments a reader marks: starred lines and bookmarks, each answered with the reader's marks. */
function useMarks(meetingId: string) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const failureText = useFailureText();
  const starred = (marked: string[]) =>
    patchMeeting(cache, meetingId, (current) => ({ ...current, starred: marked }));
  // The page is as long as the meeting, so a failure is said over it and not at its top.
  const onError = (error: unknown) => notify({ title: failureText(error), tone: "error" });
  /** A star shows as soon as it is pressed and is taken back if the server refuses it. */
  const starring = (star: boolean) => ({
    onMutate: ({ path }: { path: { utteranceId: string } }) => {
      const before = cache.getQueryData<MeetingDetail>(meetingQueryKey(meetingId))?.starred;
      patchMeeting(cache, meetingId, (current) => ({
        ...current,
        starred: star
          ? [...current.starred, path.utteranceId]
          : current.starred.filter((id) => id !== path.utteranceId),
      }));
      return before;
    },
    onSuccess: starred,
    onError: (error: unknown, _sent: unknown, before: string[] | undefined) => {
      if (before) starred(before);
      onError(error);
    },
  });
  return {
    star: useMutation({ ...starMeetingUtteranceMutation(), ...starring(true) }),
    unstar: useMutation({ ...unstarMeetingUtteranceMutation(), ...starring(false) }),
    bookmark: useMutation({
      ...bookmarkMeetingMomentMutation(),
      onSuccess: (bookmarks, { body }) => {
        patchMeeting(cache, meetingId, (current) => ({ ...current, bookmarks }));
        // The timeline it joins may be closed or out of view while the meeting is recorded.
        notify({
          title: ui("Đã đánh dấu thời điểm {{time}}", { time: formatClock(body.atMs) }),
          tone: "success",
        });
      },
      onError,
    }),
  };
}

/** The meeting page's data and every action it takes, with the failure of the last one. */
export function useMeetingPage(
  meetingId: string,
  tabAudioMissing: boolean,
  /** The tab the address names, and how the page names another. */
  pane: MeetingPane | undefined,
  setPane: (pane: MeetingPane) => void,
) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const failureText = useFailureText();
  const meeting = useQuery({
    ...getMeetingOptions({ path: { meetingId } }),
    // Work without a socket lands on its own; the page asks again until it has.
    refetchInterval: (query) => (waiting(query.state.data) ? 3000 : false),
  });
  const live = useLiveRecording(meetingId);
  const [tabMissing, setTabMissing] = useState(tabAudioMissing);
  const [target, setTarget] = useState<TranscriptTarget>();
  const { star, unstar, bookmark } = useMarks(meetingId);

  const resume = useMutation({
    mutationFn: async (data: MeetingDetail) => {
      const sources = await openMeetingSources(data.kind);
      const offsets = trackOffsets(data);
      await startRecording(
        meetingId,
        [
          { track: "MIC", stream: sources.microphone, offsetMs: offsets.MIC },
          ...(sources.tab
            ? [{ track: "TAB" as const, stream: sources.tab, offsetMs: offsets.TAB }]
            : []),
        ],
        cache,
      );
      return data.kind === "ONLINE" && !sources.tab;
    },
    onSuccess: setTabMissing,
  });
  // The confirmation closes at once; the recording bar shows the last words being stored.
  const end = useMutation({ mutationFn: () => endMeeting(meetingId, cache) });
  const shareTab = useMutation({
    mutationFn: async (recorder: MeetingRecorder) => {
      try {
        const tab = await pickMeetingTab();
        if (tab) await recorder.replaceTab(tab);
        return !tab;
      } catch (failed) {
        if (failed instanceof ShareCancelledError) return tabMissing;
        throw failed;
      }
    },
    onSuccess: setTabMissing,
  });
  const remove = useMutation({
    ...deleteMeetingMutation(),
    onSuccess: async () => {
      cache.removeQueries({ queryKey: meetingQueryKey(meetingId) });
      void invalidateMeetingList(cache);
      await navigate({ to: "/meetings" });
    },
  });

  const actions = [resume, end, shareTab];
  /** One action at a time speaks: starting one clears what the last one said. */
  const clear = () => {
    for (const action of actions) action.reset();
  };

  function failureOf() {
    if (resume.isError)
      return resume.error instanceof ShareCancelledError
        ? ui("Bạn chưa chọn tab cuộc họp nên chưa bắt đầu ghi.")
        : resume.error instanceof DOMException
          ? ui("Trình duyệt không cho dùng micro. Hãy cho phép micro rồi thử lại.")
          : failureText(resume.error);
    if (end.isError) return ui("Chưa kết thúc được cuộc họp. Hãy thử lại.");
    if (shareTab.isError) return ui("Không chia sẻ được tab. Hãy thử lại.");
    return undefined;
  }

  return {
    meeting,
    live,
    tabMissing,
    target,
    pane,
    setPane,
    resuming: resume.isPending,
    actionError: failureOf(),
    remove,
    resume: (data: MeetingDetail) => {
      clear();
      resume.mutate(data);
    },
    end: async () => {
      clear();
      end.mutate();
    },
    shareTab: () => {
      if (!live.recorder) return;
      clear();
      shareTab.mutate(live.recorder);
    },
    star: (utteranceId: string, starred: boolean) => {
      (starred ? star : unstar).mutate({ path: { meetingId, utteranceId } });
    },
    bookmark: (atMs: number) => {
      bookmark.mutate({ path: { meetingId }, body: { atMs, label: null } });
    },
    /**
     * Opens the transcript on one line, from wherever the page quotes it: a quote lands in the middle of the
     * window, a place in the timeline at its top, where reading goes on from.
     */
    reveal: (utteranceId: string, block: TranscriptTarget["block"] = "center") => {
      setPane("transcript");
      setTarget((current) => ({ utteranceId, block, seq: (current?.seq ?? 0) + 1 }));
    },
  };
}

export type MeetingPageState = ReturnType<typeof useMeetingPage>;
