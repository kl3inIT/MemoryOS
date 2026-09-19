import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SpeechChunker } from "./tts-chunker";

function chunker() {
  const parts: string[] = [];
  return { parts, speech: new SpeechChunker((part) => parts.push(part)) };
}

describe("speech chunker", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("keeps short sentences together and cuts at the first sentence end after thirty characters", () => {
    const { parts, speech } = chunker();
    speech.update("Xin chào. Đây là câu trả lời đầu tiên của trợ lý. Tiếp");
    expect(parts).toEqual(["Xin chào. Đây là câu trả lời đầu tiên của trợ lý."]);
    speech.update("Xin chào. Đây là câu trả lời đầu tiên của trợ lý. Tiếp theo là phần hai, rồi");
    expect(parts).toHaveLength(1);
  });

  it("starts early with the first words and flushes a finished sentence after a pause", () => {
    const { parts, speech } = chunker();
    speech.update("Một hai ba bốn năm sáu bảy tám chín mười");
    expect(parts).toEqual([]);
    vi.advanceTimersByTime(200);
    expect(parts).toEqual(["Một hai ba bốn năm sáu bảy tám chín"]);
    speech.update("Một hai ba bốn năm sáu bảy tám chín mười.");
    expect(parts).toHaveLength(1);
    vi.advanceTimersByTime(250);
    expect(parts).toEqual(["Một hai ba bốn năm sáu bảy tám chín", "mười."]);
  });

  it("cuts a long clause, or a long run of words, when there is no sentence end", () => {
    const clause = chunker();
    clause.speech.update(`${"a".repeat(99)}, ${"b".repeat(60)}`);
    expect(clause.parts).toEqual([`${"a".repeat(99)},`]);

    const words = chunker();
    words.speech.update(Array.from({ length: 41 }, () => "word").join(" "));
    expect(words.parts).toEqual([Array.from({ length: 24 }, () => "word").join(" ")]);
  });

  it("sends the rest on completion and nothing afterwards", () => {
    const { parts, speech } = chunker();
    speech.update("Ngắn");
    speech.complete("Ngắn gọn thôi");
    expect(parts).toEqual(["Ngắn gọn thôi"]);
    expect(speech.count).toBe(1);
    speech.update("Ngắn gọn thôi. Phần thêm này không được đọc nữa.");
    vi.advanceTimersByTime(1_000);
    expect(parts).toHaveLength(1);
  });
});
