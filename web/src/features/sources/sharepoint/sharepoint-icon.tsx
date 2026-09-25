import type { SVGProps } from "react";

/**
 * The SharePoint product mark, used to name the connected product as Microsoft's brand guidance
 * allows. It is drawn inline for the same reason the Drive mark is: no runtime asset fetch.
 */
export function SharePointIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg" {...props}>
      <circle cx="26" cy="14" r="11" fill="#036C70" />
      <circle cx="34" cy="26" r="10" fill="#1A9BA1" />
      <circle cx="26" cy="35" r="8" fill="#37C6D0" />
      <path
        d="M4 16.5A2.5 2.5 0 0 1 6.5 14h18a2.5 2.5 0 0 1 2.5 2.5v18a2.5 2.5 0 0 1-2.5 2.5h-18A2.5 2.5 0 0 1 4 34.5Z"
        fill="#03787C"
      />
      <path
        d="M15.9 24.6c-1.6-.6-2.1-1-2.1-1.7 0-.7.6-1.2 1.8-1.2 1 0 1.9.3 2.8.8v-2.3a7.4 7.4 0 0 0-2.8-.5c-2.7 0-4.4 1.4-4.4 3.4 0 1.8 1.1 2.8 3.3 3.6 1.6.6 2.1 1 2.1 1.8 0 .8-.7 1.3-1.9 1.3-1.2 0-2.4-.4-3.4-1.2v2.5c1 .5 2.2.8 3.4.8 2.9 0 4.6-1.4 4.6-3.6 0-1.8-1-2.8-3.4-3.7Z"
        fill="#FFFFFF"
      />
    </svg>
  );
}
