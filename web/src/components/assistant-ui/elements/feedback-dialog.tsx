// Adapted from assistant-ui FeedbackDialog (MIT), revision 2c22f5d7.
// Dialog submission owns pending/error state; the server accepts one reason per response.
import { Button } from "@/components/ui/button";

export function FeedbackDialog({
  reasons,
  selected,
  note,
  onToggleReason,
  onNoteChange,
}: {
  reasons: readonly string[];
  selected: string;
  note: string;
  onToggleReason: (reason: string) => void;
  onNoteChange: (note: string) => void;
}) {
  return (
    <div data-slot="feedback-dialog" className="flex flex-col gap-4">
      <p className="text-sm text-content-secondary">Thêm lý do hoặc góp ý (không bắt buộc).</p>
      <div role="group" aria-label="Lý do đánh giá" className="flex flex-wrap gap-2">
        {reasons.map((reason) => (
          <Button
            key={reason}
            type="button"
            size="sm"
            prominence={selected === reason ? "primary" : "secondary"}
            aria-pressed={selected === reason}
            onClick={() => onToggleReason(selected === reason ? "" : reason)}
          >
            {reason}
          </Button>
        ))}
      </div>
      <textarea
        aria-label="Góp ý"
        placeholder="Bạn muốn câu trả lời tốt hơn ở điểm nào?"
        value={note}
        rows={3}
        maxLength={4000}
        onChange={(event) => onNoteChange(event.target.value)}
        className="w-full resize-y rounded-xl border border-border-default bg-surface-base px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
      />
    </div>
  );
}
