// Adapted from assistant-ui FeedbackDialog (MIT), revision 2c22f5d7.
// Dialog submission owns pending/error state; the server accepts one reason per response.
import { Button } from "@/components/ui/button";
import { useTranslation } from "react-i18next";

export function FeedbackDialog({
  reasons,
  selected,
  note,
  onToggleReason,
  onNoteChange,
}: {
  reasons: readonly { id: string; label: string }[];
  selected: string;
  note: string;
  onToggleReason: (reason: string) => void;
  onNoteChange: (note: string) => void;
}) {
  const { t } = useTranslation("feedback");
  return (
    <div data-slot="feedback-dialog" className="flex flex-col gap-4">
      <p className="text-sm text-content-secondary">{t("optional")}</p>
      <div role="group" aria-label={t("reasons")} className="flex flex-wrap gap-2">
        {reasons.map((reason) => (
          <Button
            key={reason.id}
            type="button"
            size="sm"
            className="max-w-full rounded-full whitespace-normal text-start"
            prominence={selected === reason.id ? "primary" : "secondary"}
            aria-pressed={selected === reason.id}
            onClick={() => onToggleReason(selected === reason.id ? "" : reason.id)}
          >
            {reason.label}
          </Button>
        ))}
      </div>
      <textarea
        aria-label={t("note")}
        placeholder={t("placeholder")}
        value={note}
        rows={3}
        maxLength={4000}
        onChange={(event) => onNoteChange(event.target.value)}
        className="w-full resize-y rounded-xl border border-border-default bg-surface-base px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
      />
    </div>
  );
}
