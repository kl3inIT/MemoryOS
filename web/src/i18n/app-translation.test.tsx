import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { i18n } from "./index";
import { appText } from "./app-text";
import { useAppTranslation } from "./use-app-translation";
import { appEn, appVi } from "./app-translations";
import { capabilityCopy } from "@/features/groups/group-capability-copy";
import { sourceProviders } from "@/features/sources/source-provider-catalog";
import { sourceStatusMessage } from "@/features/sources/source-errors";
import { formatUiDate } from "./format";

describe("full application presentation boundary", () => {
  it("has both catalogs for registry capabilities and provider labels", () => {
    for (const key of [
      ...Object.values(capabilityCopy).flatMap((copy) => [copy.label, copy.description]),
      ...sourceProviders.flatMap((provider) => [provider.name, provider.category]),
    ]) {
      expect(appEn[key], key).toBeTruthy();
      expect(appVi[key], key).toBeTruthy();
    }
  });
  it("retranslates nested safe error descriptors and formats dates without translating user data", async () => {
    await i18n.changeLanguage("en");
    const { result } = renderHook(() => useAppTranslation());
    const descriptor = appText(
      "{{filename}}: processing may still be running. Refresh the source to check its status.",
      { filename: "Sources" },
    );
    expect(result.current(descriptor)).toBe(
      "Sources: processing may still be running. Refresh the source to check its status.",
    );
    const date = formatUiDate("2026-09-12T00:00:00Z", { dateStyle: "long", timeZone: "UTC" });
    await act(() => i18n.changeLanguage("vi"));
    expect(result.current(descriptor)).toBe(
      "Sources: có thể vẫn đang xử lý. Làm mới nguồn để kiểm tra trạng thái.",
    );
    expect(result.current("Sources")).toBe("Nguồn dữ liệu");
    expect(formatUiDate("2026-09-12T00:00:00Z", { dateStyle: "long", timeZone: "UTC" })).not.toBe(
      date,
    );
    expect(result.current(sourceStatusMessage("SOURCE_PROVIDER_RATE_LIMITED"))).toContain(
      "SOURCE_PROVIDER_RATE_LIMITED",
    );
    expect(result.current(sourceStatusMessage("private backend diagnostic"))).not.toContain(
      "private backend diagnostic",
    );
  });
});
