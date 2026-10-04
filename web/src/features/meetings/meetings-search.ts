import { z } from "zod";

/** How far back the list looks; a month unless the address says otherwise. */
export const MEETING_PERIOD_DAYS = { month: 30, quarter: 90, all: undefined } as const;

/**
 * The list's filters, kept in the address so that coming back from a meeting, a reload or a shared link shows the
 * same list. A filter left at its default is left out.
 */
export const meetingsSearchSchema = z.object({
  // The router reads a name made of digits as a number.
  q: z
    .preprocess(
      (value) => (typeof value === "number" ? String(value) : value),
      z.string().max(200).optional(),
    )
    .catch(undefined),
  status: z.enum(["RECORDING", "TRANSCRIBING", "ENDED"]).optional().catch(undefined),
  period: z.enum(["quarter", "all"]).optional().catch(undefined),
});

export type MeetingsSearch = z.output<typeof meetingsSearchSchema>;
