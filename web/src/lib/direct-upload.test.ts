import { afterEach, describe, expect, it, vi } from "vitest";
import type { SourceUploadAuthorization } from "@/lib/hey-api/types.gen";
import { DirectUploadError, putAuthorizedObject, sha256 } from "./direct-upload";

class FakeXmlHttpRequest extends EventTarget {
  static latest: FakeXmlHttpRequest;

  readonly upload = new EventTarget();
  readonly headers = new Map<string, string>();
  status = 0;
  method = "";
  url = "";
  body: Document | XMLHttpRequestBodyInit | null = null;

  constructor() {
    super();
    FakeXmlHttpRequest.latest = this;
  }

  open(method: string, url: string | URL) {
    this.method = method;
    this.url = url.toString();
  }

  setRequestHeader(name: string, value: string) {
    this.headers.set(name, value);
  }

  send(body: Document | XMLHttpRequestBodyInit | null) {
    this.body = body;
  }

  abort() {
    this.dispatchEvent(new Event("abort"));
  }
}

const authorization: SourceUploadAuthorization = {
  uploadId: "ac15afe3-88b3-4627-a737-51d8c4c1b290",
  method: "PUT",
  uploadUrl: "https://objects.example.test/memoryos/raw/upload",
  requiredHeaders: {
    "content-type": "text/plain",
    "x-amz-checksum-sha256": "checksum",
  },
  expiresAt: "2026-08-27T10:10:00Z",
};

describe("direct object upload", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("computes the lowercase SHA-256 declared to the API", async () => {
    const file = new File(["abc"], "test.txt", { type: "text/plain" });
    const checksum = await sha256(file, new AbortController().signal);

    expect(checksum).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });

  it("uses only authorization fields and reports transport progress", async () => {
    vi.stubGlobal("XMLHttpRequest", FakeXmlHttpRequest);
    const progress: number[] = [];
    const file = new File(["0123456789"], "test.txt", { type: "text/plain" });
    const completion = putAuthorizedObject(
      authorization,
      file,
      new AbortController().signal,
      (percent) => progress.push(percent),
    );
    const request = FakeXmlHttpRequest.latest;

    request.upload.dispatchEvent(
      new ProgressEvent("progress", { lengthComputable: true, loaded: 5, total: 10 }),
    );
    request.status = 200;
    request.dispatchEvent(new Event("load"));
    await completion;

    expect(request.method).toBe("PUT");
    expect(request.url).toBe(authorization.uploadUrl);
    expect(request.headers).toEqual(new Map(Object.entries(authorization.requiredHeaders)));
    expect(request.body).toBe(file);
    expect(progress).toEqual([50, 100]);
  });

  it("surfaces provider rejection and cancellation distinctly", async () => {
    vi.stubGlobal("XMLHttpRequest", FakeXmlHttpRequest);
    const file = new File(["content"], "test.txt", { type: "text/plain" });
    const rejected = putAuthorizedObject(
      authorization,
      file,
      new AbortController().signal,
      vi.fn(),
    );
    FakeXmlHttpRequest.latest.status = 403;
    FakeXmlHttpRequest.latest.dispatchEvent(new Event("load"));
    await expect(rejected).rejects.toEqual(
      new DirectUploadError(403, "Object storage rejected the upload"),
    );

    const controller = new AbortController();
    const cancelled = putAuthorizedObject(authorization, file, controller.signal, vi.fn());
    controller.abort(new DOMException("Upload cancelled", "AbortError"));
    await expect(cancelled).rejects.toMatchObject({ name: "AbortError" });
  });
});
