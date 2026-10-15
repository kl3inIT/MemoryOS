import type { SVGProps } from "react";

/** Part of the mark, not copy: it reads the same in every language. */
const WORDMARK = "Zalo";

/**
 * A mark for Zalo in its product colors: the white speech bubble with the wordmark on Zalo blue. It is drawn
 * inline for the same reason the Lark mark is: no runtime asset fetch.
 */
export function ZaloIcon(props: SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg" {...props}>
      <rect width="48" height="48" rx="11" fill="#0068FF" />
      <path
        d="M24 9.5c-9.7 0-17 6.2-17 13.9 0 4.4 2.4 8.3 6.2 10.9l-1.6 4.6c-.2.6.4 1.1 1 .8l5.4-2.8c1.9.5 3.9.8 6 .8 9.7 0 17-6.2 17-13.9C41 15.7 33.7 9.5 24 9.5Z"
        fill="#fff"
      />
      <text
        x="24"
        y="27.6"
        textAnchor="middle"
        fill="#0068FF"
        fontFamily="Arial, Helvetica, sans-serif"
        fontSize="10.5"
        fontWeight="700"
      >
        {WORDMARK}
      </text>
    </svg>
  );
}
