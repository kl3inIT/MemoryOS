import { useState, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Undo2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { revertMeetingCorrectionMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import { foldCorrection, type MeetingCorrection } from "./meetings-api";
import { useFailureText } from "./use-failure-text";

/**
 * Words the owner changed, opened where they stand in the line: what the transcriber heard there, and the way back
 * to it. A change is read and undone in the sentence it belongs to, however many the meeting has.
 */
export function AppliedCorrection({
  meetingId,
  correction,
  children,
}: {
  meetingId: string;
  correction: MeetingCorrection;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const failureText = useFailureText();
  const [open, setOpen] = useState(false);
  const revert = useMutation({
    ...revertMeetingCorrectionMutation(),
    onSuccess: (answer) => foldCorrection(cache, meetingId, answer),
  });

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) revert.reset();
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          className="rounded-sm focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:outline-none"
          aria-label={ui("Đã sửa “{{before}}” thành “{{after}}”", {
            before: correction.before,
            after: correction.after,
          })}
        >
          {children}
        </button>
      </PopoverTrigger>
      {/* Read as the change it is: what was heard, what it became, and the way back, on one row. */}
      <PopoverContent align="start" className="w-auto max-w-80 p-2">
        <div className="flex items-center gap-2 pl-1 text-sm">
          <span className="text-content-muted line-through">{correction.before}</span>
          <ArrowRight aria-hidden="true" className="size-3.5 shrink-0 text-content-muted" />
          <span className="font-medium text-content-primary">{correction.after}</span>
          <Button
            prominence="tertiary"
            size="sm"
            className="ml-2"
            disabled={revert.isPending}
            onClick={() => revert.mutate({ path: { meetingId, correctionId: correction.id } })}
          >
            <Undo2 aria-hidden="true" />
            {ui("Hoàn tác")}
          </Button>
        </div>
        {revert.isError && (
          <p role="alert" className="mt-1 px-1 text-xs text-status-danger-content">
            {failureText(revert.error)}
          </p>
        )}
      </PopoverContent>
    </Popover>
  );
}
