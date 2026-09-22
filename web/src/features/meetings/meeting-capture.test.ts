import { describe, expect, it, vi } from "vitest";
import { captureSupport, pickMeetingTab, ShareCancelledError } from "./meeting-capture";

const media = { getUserMedia: vi.fn(), getDisplayMedia: vi.fn() } as unknown as MediaDevices;
const agents = {
  chromeDesktop:
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36",
  edgeDesktop:
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36 Edg/141.0",
  firefox: "Mozilla/5.0 (Windows NT 10.0; rv:131.0) Gecko/20100101 Firefox/131.0",
  safari:
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
  chromeAndroid:
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Mobile Safari/537.36",
  safariIphone:
    "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
};

function track(kind: "audio" | "video") {
  return { kind, stop: vi.fn() } as unknown as MediaStreamTrack;
}

describe("meeting capture", () => {
  it("follows Nojoin's browser support: shared audio on desktop Chromium, microphone on mobile Chrome, otherwise none", () => {
    expect(captureSupport({ userAgent: agents.chromeDesktop, mediaDevices: media })).toBe(
      "sharedAudio",
    );
    expect(captureSupport({ userAgent: agents.edgeDesktop, mediaDevices: media })).toBe(
      "sharedAudio",
    );
    expect(captureSupport({ userAgent: agents.chromeAndroid, mediaDevices: media })).toBe(
      "microphoneOnly",
    );
    expect(captureSupport({ userAgent: agents.firefox, mediaDevices: media })).toBe("unsupported");
    expect(captureSupport({ userAgent: agents.safari, mediaDevices: media })).toBe("unsupported");
    expect(captureSupport({ userAgent: agents.safariIphone, mediaDevices: media })).toBe(
      "unsupported",
    );
  });

  it("keeps only the shared tab's audio and says when its audio was not shared", async () => {
    const video = track("video");
    const audio = track("audio");
    const withAudio = {
      getDisplayMedia: vi
        .fn()
        .mockResolvedValue({ getVideoTracks: () => [video], getAudioTracks: () => [audio] }),
    } as unknown as MediaDevices;
    vi.stubGlobal(
      "MediaStream",
      class {
        readonly tracks: MediaStreamTrack[];
        constructor(tracks: MediaStreamTrack[]) {
          this.tracks = tracks;
        }
      },
    );
    const stream = (await pickMeetingTab(withAudio)) as unknown as { tracks: MediaStreamTrack[] };
    expect(video.stop).toHaveBeenCalled();
    expect(stream.tracks).toEqual([audio]);

    const silent = {
      getDisplayMedia: vi
        .fn()
        .mockResolvedValue({ getVideoTracks: () => [track("video")], getAudioTracks: () => [] }),
    } as unknown as MediaDevices;
    await expect(pickMeetingTab(silent)).resolves.toBeNull();
    vi.unstubAllGlobals();
  });

  it("turns a closed share picker into a cancellation", async () => {
    const cancelled = {
      getDisplayMedia: vi.fn().mockRejectedValue(new DOMException("denied", "NotAllowedError")),
    } as unknown as MediaDevices;
    await expect(pickMeetingTab(cancelled)).rejects.toBeInstanceOf(ShareCancelledError);
  });
});
