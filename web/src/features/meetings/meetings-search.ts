import { z } from "zod";

/** The list's filters, kept in the address so Back from a meeting returns to the same list. */
export const meetingsSearchSchema = z.object({
  q: z
    .preprocess(
      (value) => (typeof value === "string" && value.trim() ? value : undefined),
      z.string().max(200).optional(),
    )
    .catch(undefined),
  status: z.enum(["RECORDING", "TRANSCRIBING", "ENDED"]).optional().catch(undefined),
  /** Absent means the last 30 days, the period the list opens with. */
  period: z.enum(["90d", "all"]).optional().catch(undefined),
});

export type MeetingsSearch = z.output<typeof meetingsSearchSchema>;
export type MeetingStatusFilter = NonNullable<MeetingsSearch["status"]>;
export type MeetingPeriod = NonNullable<MeetingsSearch["period"]> | "30d";
