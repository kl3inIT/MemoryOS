/**
 * Brand marks live as static assets under `public/provider-logos` and identify the
 * provider a connection talks to. A monochrome mark follows the theme; a brand-coloured
 * mark is shown as published.
 */
export const providerMarks = {
  BRAVE: { file: "brave.svg", monochrome: false },
  EXA: { file: "exa.png", monochrome: false },
  FIRECRAWL: { file: "firecrawl.png", monochrome: false },
  GOOGLE_PSE: { file: "google.svg", monochrome: false },
  SEARXNG: { file: "searxng.svg", monochrome: false },
  SERPER: { file: "serper.png", monochrome: false },
  TAVILY: { file: "tavily.svg", monochrome: false },
  OPENAI: { file: "openai.svg", monochrome: true },
} as const;

export type ProviderMark = keyof typeof providerMarks;

export function hasProviderMark(mark: string): mark is ProviderMark {
  return mark in providerMarks;
}
