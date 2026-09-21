/**
 * Browser sources for a meeting, following Nojoin's capture (featureDetect.ts, pickSource.ts): Chromium on a desktop
 * can share a meeting tab with its audio; Chrome on a phone records the microphone only; other browsers cannot record.
 */

export type CaptureSupport = "sharedAudio" | "microphoneOnly" | "unsupported";

type NavigatorLike = Pick<Navigator, "userAgent" | "mediaDevices"> & {
  userAgentData?: { mobile?: boolean; brands?: { brand: string }[] };
};

/** Nojoin's order: mobile Chrome is microphone-only; Firefox, Safari and other mobiles are unsupported. */
export function captureSupport(nav: NavigatorLike = navigator): CaptureSupport {
  const agent = nav.userAgent;
  const media = nav.mediaDevices;
  const hasMicrophone = typeof media?.getUserMedia === "function";
  const hasDisplay = typeof media?.getDisplayMedia === "function";
  const mobile = nav.userAgentData?.mobile ?? /Android|iPhone|iPad|iPod|Mobile/i.test(agent);
  const firefox = /Firefox\//.test(agent);
  const chromium =
    nav.userAgentData?.brands?.some((brand) =>
      /Chromium|Google Chrome|Microsoft Edge/.test(brand.brand),
    ) ??
    (/Chrome\/|CriOS\/|Edg\//.test(agent) && !/OPR\//.test(agent));
  if (mobile) return chromium && hasMicrophone ? "microphoneOnly" : "unsupported";
  if (firefox || !chromium || !hasMicrophone) return "unsupported";
  return hasDisplay ? "sharedAudio" : "microphoneOnly";
}

/** The person closed the share picker; nothing was started. */
export class ShareCancelledError extends Error {
  constructor(cause: unknown) {
    super("Share picker cancelled", { cause });
    this.name = "ShareCancelledError";
  }
}

/**
 * Asks the browser to share a meeting tab with its audio. The video track is not needed and is stopped at once
 * (opennotetaker), but Chrome only offers the audio checkbox when video is requested. Returns the audio-only stream,
 * or null when the person did not tick "share tab audio".
 */
export async function pickMeetingTab(
  media: MediaDevices = navigator.mediaDevices,
): Promise<MediaStream | null> {
  let shared: MediaStream;
  try {
    shared = await media.getDisplayMedia({
      video: true,
      // Tab and window audio are captured as-is; processing belongs to the microphone only (silent-notetaker).
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
      systemAudio: "include",
      selfBrowserSurface: "exclude",
      surfaceSwitching: "include",
    } as DisplayMediaStreamOptions);
  } catch (error) {
    throw new ShareCancelledError(error);
  }
  for (const track of shared.getVideoTracks()) track.stop();
  const audio = shared.getAudioTracks();
  if (audio.length === 0) return null;
  return new MediaStream(audio);
}

/** The microphone with the browser's echo cancellation, noise suppression and gain control, as for dictation. */
export async function openMicrophone(
  media: MediaDevices = navigator.mediaDevices,
): Promise<MediaStream> {
  return media.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
    },
  });
}

/**
 * Opens what a meeting records, in Nojoin's order: the share picker first, while the click still counts as a user
 * action, then the microphone. Online without shared audio, only the microphone is recorded.
 */
export async function openMeetingSources(
  kind: "ONLINE" | "IN_PERSON",
  media: MediaDevices = navigator.mediaDevices,
): Promise<{ microphone: MediaStream; tab: MediaStream | null }> {
  const tab = kind === "ONLINE" ? await pickMeetingTab(media) : null;
  try {
    return { microphone: await openMicrophone(media), tab };
  } catch (error) {
    for (const track of tab?.getTracks() ?? []) track.stop();
    throw error;
  }
}
