import {
  cloneElement,
  isValidElement,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
} from "react";

type ActivatableChildProps = {
  "aria-disabled"?: boolean;
  onClick?: (event: MouseEvent<HTMLElement>) => void;
  onClickCapture?: (event: MouseEvent<HTMLElement>) => void;
  onKeyDown?: (event: KeyboardEvent<HTMLElement>) => void;
  onKeyDownCapture?: (event: KeyboardEvent<HTMLElement>) => void;
  tabIndex?: number;
};

function stopActivation(event: MouseEvent<HTMLElement> | KeyboardEvent<HTMLElement>) {
  event.preventDefault();
  event.stopPropagation();
}
function stopKeyboardActivation(event: KeyboardEvent<HTMLElement>) {
  if (event.key === "Enter" || event.key === " ") stopActivation(event);
}

function disableActionChild(children: ReactNode) {
  if (!isValidElement<ActivatableChildProps>(children)) return children;

  return cloneElement(children, {
    "aria-disabled": true,
    onClick: stopActivation,
    onClickCapture: stopActivation,
    onKeyDown: stopKeyboardActivation,
    onKeyDownCapture: stopKeyboardActivation,
    tabIndex: -1,
  });
}

export { disableActionChild };
