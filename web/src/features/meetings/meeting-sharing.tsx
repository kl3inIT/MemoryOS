import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { MeetingShareField, type MeetingAudience } from "./meeting-share-field";
import { patchMeeting, shareMeeting, type MeetingDetail } from "./meetings-api";

/** Who else reads this meeting. Only its owner sees, or changes, this list. */
export function MeetingSharing({ meeting }: { meeting: MeetingDetail }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const [pending, setPending] = useState(false);
  const audience = {
    people: meeting.readers
      .filter((reader) => reader.kind === "MEMBER")
      .map((reader) => ({ actorId: reader.id, name: reader.name, email: null })),
    groups: meeting.readers
      .filter((reader) => reader.kind === "GROUP")
      .map((reader) => ({ id: reader.id, name: reader.name })),
  };

  async function save(next: MeetingAudience) {
    setPending(true);
    try {
      const readers = await shareMeeting(
        meeting.id,
        next.people.map((person) => person.actorId),
        next.groups.map((group) => group.id),
      );
      patchMeeting(cache, meeting.id, (current) => ({ ...current, readers }));
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="grid gap-2 rounded-xl border border-border-subtle px-4 py-4">
      <MeetingShareField
        label={ui("Chia sẻ")}
        value={audience}
        disabled={pending}
        onChange={(next) => void save(next)}
      />
    </section>
  );
}
