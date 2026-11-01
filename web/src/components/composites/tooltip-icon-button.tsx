import type { ComponentProps, ReactNode } from "react";
import { IconButton } from "@/components/ui/icon-button";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";

/**
 * An `IconButton` that shows a tooltip on hover and keyboard focus, so sight and speech name it alike, unlike a
 * native `title`. The tooltip says what the button does: its `aria-label` unless `tooltip` gives a shorter form, as
 * for a row action whose label also names the row. Its own provider lets it work in the shell header too, as
 * `SourceHint` does. It lives outside `IconButton` so the tooltip's positioning code loads only with the screens
 * that use it.
 */
export function TooltipIconButton({
  tooltip,
  ...props
}: ComponentProps<typeof IconButton> & { tooltip?: ReactNode }) {
  return (
    <TooltipProvider delayDuration={300}>
      <Tooltip>
        <TooltipTrigger asChild>
          <IconButton {...props} />
        </TooltipTrigger>
        <TooltipContent>{tooltip ?? props["aria-label"]}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
