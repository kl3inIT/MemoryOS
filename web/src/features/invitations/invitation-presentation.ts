const invitationDateFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});

export function formatInvitationDate(value: string) {
  return invitationDateFormatter.format(new Date(value));
}
