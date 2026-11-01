import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { i18n } from "@/i18n/index";
import { EvidenceViewSwitch } from "./evidence-view-switch";
import { readSourceLocation } from "./source-provenance";

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  // jsdom has no object URLs.
  URL.createObjectURL = () => "blob:picture";
  URL.revokeObjectURL = vi.fn();
});
afterEach(() => {
  Reflect.deleteProperty(URL, "createObjectURL");
  Reflect.deleteProperty(URL, "revokeObjectURL");
  cleanup();
});

it("opens a cited picture on its original with the recorded region marked, as the Chat source panel shows it", async () => {
  const location = readSourceLocation([
    '{"page_no":1,"bbox":{"l":100,"t":300,"r":500,"b":900,"coord_origin":"TOPLEFT","frame_width":1000,"frame_height":2000}}',
  ]);
  render(
    <QueryClientProvider client={new QueryClient()}>
      <EvidenceViewSwitch
        original={{
          reader: {
            url: "/api/chat/documents/1/original?generation=2",
            bytes: () => Promise.resolve(new Blob(["picture"], { type: "image/png" })),
            sheets: () => Promise.reject(new Error("not a workbook")),
          },
          filename: "hoa-don.png",
          mediaType: "image/png",
          pages: location.pages,
          boxes: location.boxes,
          citations: ["Tổng cộng 1.200.000"],
        }}
      >
        <p>Tổng cộng 1.200.000</p>
      </EvidenceViewSwitch>
    </QueryClientProvider>,
  );

  // The readers' code is a chunk of its own, loaded when the original is first shown.
  const picture = await screen.findByRole("img", { name: "hoa-don.png" }, { timeout: 20_000 });
  Object.defineProperty(picture, "naturalWidth", { value: 1000 });
  Object.defineProperty(picture, "naturalHeight", { value: 2000 });
  fireEvent.load(picture);

  expect(screen.getByRole("img", { name: "Vùng được trích dẫn trên ảnh" })).toHaveStyle({
    left: "10%",
    top: "15%",
    width: "40%",
    height: "30%",
  });
}, 30_000);
