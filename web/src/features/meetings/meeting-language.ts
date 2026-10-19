/** What a meeting is spoken in; `auto` leaves the transcriber to tell Vietnamese and English apart. */
export type MeetingLanguage = "vi" | "en" | "auto";

/** The language is chosen before every meeting and locked once it starts, so the last choice is kept (Fireflies). */
const LANGUAGE_KEY = "memoryos.meeting.language";

export function rememberedLanguage(): MeetingLanguage {
  try {
    const stored = localStorage.getItem(LANGUAGE_KEY);
    if (stored === "vi" || stored === "en" || stored === "auto") return stored;
  } catch {
    // Storage can be unavailable; Vietnamese is the default.
  }
  return "vi";
}

export function rememberLanguage(language: MeetingLanguage) {
  try {
    localStorage.setItem(LANGUAGE_KEY, language);
  } catch {
    // A private window keeps the choice for this meeting only.
  }
}

/** The language a new meeting is created with: none when the transcriber is to tell them apart. */
export function languageCode(language: MeetingLanguage) {
  return language === "auto" ? undefined : language;
}
