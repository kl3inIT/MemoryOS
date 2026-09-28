import { render } from "@testing-library/react";
import { expect, it } from "vitest";
import { uiLocale } from "@/i18n/format";
import { Said } from "./transcript-text";

it("marks only the characters the provider was unsure of, and reads the line whole either way", () => {
  const text = "Nó ra tiếng nước ngoài.";
  const marked = render(<Said text={text} spans={[{ start: 3, end: 11, confidence: 0.41 }]} />);

  expect(marked.container.textContent).toBe(text);
  const mark = marked.container.querySelector("mark");
  expect(mark?.textContent).toBe("ra tiếng");
  expect(mark?.getAttribute("title")).toBe(
    new Intl.NumberFormat(uiLocale(), { style: "percent" }).format(0.41),
  );

  const plain = render(<Said text={text} spans={[]} />);
  expect(plain.container.textContent).toBe(text);
  expect(plain.container.querySelector("mark")).toBeNull();
});

it("ignores a stretch that falls outside the line it was given", () => {
  // A line is trimmed and capped after the provider wrote it; a stale offset must never cut the text short.
  const view = render(
    <Said
      text="Khê rồi."
      spans={[
        { start: 0, end: 3, confidence: 0.3 },
        { start: 40, end: 60, confidence: 0.2 },
      ]}
    />,
  );

  expect(view.container.textContent).toBe("Khê rồi.");
  expect(view.container.querySelectorAll("mark")).toHaveLength(1);
});
