import type { ComponentProps } from "react";
import { IconButton } from "@/components/ui/icon-button";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";

/**
 * An `IconButton` that shows its `aria-label` in a tooltip on hover and keyboard focus, so sight and speech name it
 * alike, unlike a native `title`. Its own provider lets it work in the shell header too, as `SourceHint` does.
 * It lives outside `IconButton` so the tooltip's positioning code loads only with the screens that use it.
 */
export function TooltipIconButton(props: ComponentProps<typeof IconButton>) {
  return (
    <TooltipProvider delayDuration={300}>
      <Tooltip>
        <TooltipTrigger asChild>
          <IconButton {...props} />
        </TooltipTrigger>
        <TooltipContent>{props["aria-label"]}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
