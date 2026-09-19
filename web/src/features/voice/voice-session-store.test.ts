import { expect, it, vi } from "vitest";
import { MicrophoneUnavailableError } from "./capture/audio-capture";
import { VoiceStreamError } from "./transcribe-socket";
import { voiceFailureCopy } from "./voice-failure";
import { VoiceSessionStore } from "./voice-session-store";

it("keeps a bounded meter only while recording and records every end", () => {
  const store = new VoiceSessionStore();
  store.level(0.5);
  expect(store.getSnapshot().levels).toEqual([]);

  store.starting();
  const setMuted = vi.fn();
  store.started({ setMuted });
  for (let index = 0; index < 50; index += 1) store.level(index / 100);
  expect(store.getSnapshot().levels).toHaveLength(40);
  expect(store.getSnapshot().levels.at(-1)).toBe(0.49);

  store.setMuted(true);
  expect(setMuted).toHaveBeenCalledWith(true);
  expect(store.getSnapshot().muted).toBe(true);

  const failure = new VoiceStreamError("VOICE_IDLE");
  store.ended("error", failure);
  expect(store.getSnapshot()).toMatchObject({
    phase: "idle",
    muted: false,
    failure,
    lastEnd: { reason: "error", sequence: 1 },
  });
  store.setMuted(true);
  expect(setMuted).toHaveBeenCalledOnce();

  store.starting();
  expect(store.getSnapshot().failure).toBeUndefined();
  store.ended("stopped");
  expect(store.getSnapshot().lastEnd).toEqual({ reason: "stopped", sequence: 2 });
});

it("maps dictation failures to static copy without browser or provider text", () => {
  expect(voiceFailureCopy(new MicrophoneUnavailableError(true, new Error("secret")))).toEqual({
    app: "Trình duyệt chưa cho phép dùng micro. Hãy cho phép quyền micro rồi thử lại.",
    values: undefined,
  });
  expect(voiceFailureCopy(new VoiceStreamError("VOICE_BUSY"))).toMatchObject({
    app: "Nhận dạng giọng nói đang bận. Hãy thử lại sau ít phút.",
  });
  expect(voiceFailureCopy(new Error("provider said key-123"))).toMatchObject({
    app: "Không kết nối được dịch vụ nhận dạng giọng nói. Hãy thử lại.",
  });
});
